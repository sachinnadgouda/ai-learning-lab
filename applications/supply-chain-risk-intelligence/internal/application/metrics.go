package application

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var durationBuckets = []float64{0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0}

// Metrics provides thread-safe counters, gauges, and histograms exported in Prometheus text format.
type Metrics struct {
	eventsIngested   atomic.Int64
	eventsProcessed  atomic.Int64
	eventsDuplicate  atomic.Int64
	eventsOutOfOrder atomic.Int64
	eventsDLQ        atomic.Int64
	queueDepth       atomic.Int64
	activeIncidents  atomic.Int64
	httpRequests     atomic.Int64

	mu             sync.Mutex
	histCounts     []uint64
	histSumSeconds float64
	histTotal      uint64
	aiInvocations  map[string]int64
}

func NewMetrics() *Metrics {
	return &Metrics{
		histCounts:    make([]uint64, len(durationBuckets)),
		aiInvocations: make(map[string]int64),
	}
}

func (m *Metrics) IncIngested()   { m.eventsIngested.Add(1) }
func (m *Metrics) IncProcessed()  { m.eventsProcessed.Add(1) }
func (m *Metrics) IncDuplicate()  { m.eventsDuplicate.Add(1) }
func (m *Metrics) IncOutOfOrder() { m.eventsOutOfOrder.Add(1) }
func (m *Metrics) IncDLQ()        { m.eventsDLQ.Add(1) }
func (m *Metrics) IncHTTPReq()    { m.httpRequests.Add(1) }

func (m *Metrics) SetQueueDepth(v int)      { m.queueDepth.Store(int64(v)) }
func (m *Metrics) SetActiveIncidents(v int) { m.activeIncidents.Store(int64(v)) }

func (m *Metrics) ObserveProcessingDuration(d time.Duration) {
	secs := d.Seconds()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.histTotal++
	m.histSumSeconds += secs
	for i, bound := range durationBuckets {
		if secs <= bound {
			m.histCounts[i]++
		}
	}
}

func (m *Metrics) IncAIInvocation(provider, outcome string) {
	key := fmt.Sprintf("provider=%q,outcome=%q", provider, outcome)
	m.mu.Lock()
	defer m.mu.Unlock()
	m.aiInvocations[key]++
}

// Snapshot returns basic counter values useful for tests and diagnostics.
func (m *Metrics) Snapshot() map[string]int64 {
	return map[string]int64{
		"events_ingested_total":     m.eventsIngested.Load(),
		"events_processed_total":    m.eventsProcessed.Load(),
		"events_duplicate_total":    m.eventsDuplicate.Load(),
		"events_out_of_order_total": m.eventsOutOfOrder.Load(),
		"events_dlq_total":          m.eventsDLQ.Load(),
		"queue_depth":               m.queueDepth.Load(),
		"active_incidents":          m.activeIncidents.Load(),
	}
}

// RenderPrometheus renders all metrics in Prometheus text exposition format (v0.0.4).
func (m *Metrics) RenderPrometheus() string {
	var b strings.Builder

	writeMetric := func(name, mType, help string, val int64) {
		fmt.Fprintf(&b, "# HELP %s %s\n# TYPE %s %s\n%s %d\n", name, help, name, mType, name, val)
	}

	writeMetric("events_ingested_total", "counter", "Total supply chain events accepted into the intake queue.", m.eventsIngested.Load())
	writeMetric("events_processed_total", "counter", "Total supply chain events processed by worker pool.", m.eventsProcessed.Load())
	writeMetric("events_duplicate_total", "counter", "Total duplicate supply chain events skipped via idempotency check.", m.eventsDuplicate.Load())
	writeMetric("events_out_of_order_total", "counter", "Total out-of-order events recorded without regressing newer state.", m.eventsOutOfOrder.Load())
	writeMetric("events_dlq_total", "counter", "Total events routed to the Dead-Letter Queue.", m.eventsDLQ.Load())
	writeMetric("queue_depth", "gauge", "Current number of events queued in the event broker.", m.queueDepth.Load())
	writeMetric("active_incidents", "gauge", "Current number of correlated supply chain risk incidents.", m.activeIncidents.Load())
	writeMetric("http_requests_total", "counter", "Total HTTP API requests served.", m.httpRequests.Load())

	m.mu.Lock()
	defer m.mu.Unlock()

	fmt.Fprintf(&b, "# HELP event_processing_duration_seconds Event processing duration in seconds.\n")
	fmt.Fprintf(&b, "# TYPE event_processing_duration_seconds histogram\n")
	for i, bound := range durationBuckets {
		fmt.Fprintf(&b, "event_processing_duration_seconds_bucket{le=\"%g\"} %d\n", bound, m.histCounts[i])
	}
	fmt.Fprintf(&b, "event_processing_duration_seconds_bucket{le=\"+Inf\"} %d\n", m.histTotal)
	fmt.Fprintf(&b, "event_processing_duration_seconds_sum %.6f\n", m.histSumSeconds)
	fmt.Fprintf(&b, "event_processing_duration_seconds_count %d\n", m.histTotal)

	fmt.Fprintf(&b, "# HELP ai_invocations_total Total AI investigator invocations by provider and outcome.\n")
	fmt.Fprintf(&b, "# TYPE ai_invocations_total counter\n")
	if len(m.aiInvocations) == 0 {
		fmt.Fprintf(&b, "ai_invocations_total{provider=\"none\",outcome=\"success\"} 0\n")
	} else {
		keys := make([]string, 0, len(m.aiInvocations))
		for k := range m.aiInvocations {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			fmt.Fprintf(&b, "ai_invocations_total{%s} %d\n", k, m.aiInvocations[k])
		}
	}

	return b.String()
}
