package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

type ProcessorConfig struct {
	WorkerCount    int
	MaxRetries     int
	BaseBackoff    time.Duration
	MaxBackoff     time.Duration
	ProcessTimeout time.Duration
}

func DefaultProcessorConfig() ProcessorConfig {
	return ProcessorConfig{
		WorkerCount:    4,
		MaxRetries:     3,
		BaseBackoff:    25 * time.Millisecond,
		MaxBackoff:     500 * time.Millisecond,
		ProcessTimeout: 5 * time.Second,
	}
}

// EventHook is an optional hook invoked before applying an event (used for testing transient/poison failures).
type EventHook func(ctx context.Context, ev domain.SupplyEvent, attempt int) error

// Processor runs a bounded, concurrent, idempotent, out-of-order-aware event processing worker pool with DLQ support.
type Processor struct {
	cfg       ProcessorConfig
	broker    EventBroker
	repo      Repository
	engine    *domain.RiskEngine
	notifier  Notifier
	metrics   *Metrics
	logger    *slog.Logger
	sanitizer *domain.Sanitizer
	nowFn     func() time.Time

	hookMu sync.RWMutex
	hook   EventHook

	stateMu   sync.Mutex
	alertedMu sync.Mutex
	alertedAt map[string]time.Time

	accepting atomic.Bool
	wg        sync.WaitGroup
	stopCh    chan struct{}
	stopOnce  sync.Once
}

func NewProcessor(
	cfg ProcessorConfig,
	broker EventBroker,
	repo Repository,
	engine *domain.RiskEngine,
	notifier Notifier,
	metrics *Metrics,
	logger *slog.Logger,
) *Processor {
	if cfg.WorkerCount <= 0 {
		cfg.WorkerCount = 4
	}
	if cfg.MaxRetries < 0 {
		cfg.MaxRetries = 0
	}
	if cfg.BaseBackoff <= 0 {
		cfg.BaseBackoff = 25 * time.Millisecond
	}
	if cfg.MaxBackoff <= 0 {
		cfg.MaxBackoff = 500 * time.Millisecond
	}
	if cfg.ProcessTimeout <= 0 {
		cfg.ProcessTimeout = 5 * time.Second
	}
	if engine == nil {
		engine = domain.NewRiskEngine()
	}
	if metrics == nil {
		metrics = NewMetrics()
	}
	if logger == nil {
		logger = slog.Default()
	}
	if notifier == nil {
		notifier = NewLogNotifier(logger)
	}

	p := &Processor{
		cfg:       cfg,
		broker:    broker,
		repo:      repo,
		engine:    engine,
		notifier:  notifier,
		metrics:   metrics,
		logger:    logger,
		sanitizer: domain.NewSanitizer(),
		nowFn:     func() time.Time { return time.Now().UTC() },
		alertedAt: make(map[string]time.Time),
		stopCh:    make(chan struct{}),
	}
	p.accepting.Store(true)
	return p
}

// SetEventHook installs an optional hook invoked on each processing attempt (primarily for deterministic failure tests).
func (p *Processor) SetEventHook(h EventHook) {
	p.hookMu.Lock()
	defer p.hookMu.Unlock()
	p.hook = h
}

func (p *Processor) getEventHook() EventHook {
	p.hookMu.RLock()
	defer p.hookMu.RUnlock()
	return p.hook
}

// SetClock overrides the clock (useful in deterministic tests).
func (p *Processor) SetClock(fn func() time.Time) {
	if fn != nil {
		p.nowFn = fn
	}
}

// Start launches the configured number of worker goroutines.
func (p *Processor) Start(ctx context.Context) {
	for i := 0; i < p.cfg.WorkerCount; i++ {
		p.wg.Add(1)
		go p.workerLoop(ctx, i)
	}
}

// Enqueue validates and publishes an event to the bounded broker without blocking.
// Returns domain.ErrQueueFull immediately when the intake queue is saturated (explicit backpressure).
func (p *Processor) Enqueue(ctx context.Context, ev domain.SupplyEvent) (domain.SupplyEvent, error) {
	if !p.accepting.Load() {
		return ev, fmt.Errorf("%w: processor is shutting down", domain.ErrUpstreamUnavailable)
	}
	if strings.TrimSpace(ev.EventID) == "" {
		ev.EventID = "ev-" + uuid.NewString()
	}
	if !ev.EventType.Valid() {
		return ev, fmt.Errorf("%w: unsupported event_type %q", domain.ErrInvalidInput, ev.EventType)
	}
	if strings.TrimSpace(ev.EntityID) == "" {
		return ev, fmt.Errorf("%w: entity_id is required", domain.ErrInvalidInput)
	}
	now := p.nowFn()
	if ev.OccurredAt.IsZero() {
		ev.OccurredAt = now
	} else {
		ev.OccurredAt = ev.OccurredAt.UTC()
	}
	if ev.ReceivedAt.IsZero() {
		ev.ReceivedAt = now
	} else {
		ev.ReceivedAt = ev.ReceivedAt.UTC()
	}
	if ev.Version <= 0 {
		ev.Version = 1
	}
	if ev.EntityType == "" {
		ev.EntityType = inferEntityType(ev.EventType)
	}
	if ev.Payload == nil {
		ev.Payload = map[string]any{}
	}

	if err := p.broker.Publish(ctx, ev); err != nil {
		return ev, err
	}
	p.metrics.IncIngested()
	p.metrics.SetQueueDepth(p.broker.Depth())
	return ev, nil
}

type stoppableBroker interface {
	StopReceiving()
}

// Shutdown stops accepting new events, halts new broker receives while letting in-flight workers
// finish processing and Ack cleanly, then closes the broker.
func (p *Processor) Shutdown(ctx context.Context) error {
	p.accepting.Store(false)
	p.stopOnce.Do(func() {
		close(p.stopCh)
		if sb, ok := p.broker.(stoppableBroker); ok {
			sb.StopReceiving()
		} else {
			_ = p.broker.Close()
		}
	})

	done := make(chan struct{})
	go func() {
		p.wg.Wait()
		close(done)
	}()

	var waitErr error
	select {
	case <-done:
		p.metrics.SetQueueDepth(0)
	case <-ctx.Done():
		waitErr = ctx.Err()
	}

	_ = p.broker.Close()
	return waitErr
}

func (p *Processor) workerLoop(ctx context.Context, workerID int) {
	defer p.wg.Done()

	for {
		delivery, err := p.broker.Receive(ctx)
		if err != nil {
			// Normal exit when broker is stopped/closed & drained or context cancelled.
			return
		}
		p.metrics.SetQueueDepth(p.broker.Depth())

		procCtx, cancel := context.WithTimeout(context.Background(), p.cfg.ProcessTimeout)
		p.processDelivery(procCtx, workerID, delivery)
		cancel()
	}
}

func (p *Processor) ackDelivery(ctx context.Context, d Delivery, eventID string) {
	if d.Ack == nil {
		return
	}
	if err := d.Ack(ctx); err != nil {
		p.logger.Error("broker_ack_failed", "message_id", d.MessageID, "event_id", eventID, "error", err)
	}
}

func (p *Processor) nackDelivery(ctx context.Context, d Delivery, eventID, reason string) {
	if d.Nack == nil {
		return
	}
	if err := d.Nack(ctx, reason); err != nil {
		p.logger.Error("broker_nack_failed", "message_id", d.MessageID, "event_id", eventID, "error", err)
	}
}

func (p *Processor) processDelivery(ctx context.Context, workerID int, delivery Delivery) {
	start := time.Now()
	defer func() {
		p.metrics.ObserveProcessingDuration(time.Since(start))
		p.metrics.SetQueueDepth(p.broker.Depth())
	}()

	// 0. Malformed broker messages must NEVER be silently discarded: persist to DLQ first,
	// and acknowledge ONLY if the DLQ write succeeds.
	if delivery.ParseErr != nil {
		malformedID := "malformed-" + delivery.MessageID
		if delivery.MessageID == "" {
			malformedID = "malformed-" + uuid.NewString()
		}
		p.logger.Warn("malformed_broker_message_to_dlq",
			"worker", workerID,
			"message_id", delivery.MessageID,
			"error", p.sanitizer.RedactSecrets(delivery.ParseErr.Error()),
		)
		if err := p.routeMalformedToDLQ(ctx, delivery, malformedID); err != nil {
			p.logger.Error("malformed_dlq_persist_failed_leaving_pending",
				"worker", workerID,
				"message_id", delivery.MessageID,
				"error", err,
			)
			p.nackDelivery(ctx, delivery, malformedID, err.Error())
			return
		}
		p.ackDelivery(ctx, delivery, malformedID)
		return
	}

	ev := delivery.Event

	// 1. Execute atomic processing with bounded retries (exponential backoff + jitter).
	// State mutation, event append, and idempotency claim completion commit together in one transaction.
	var lastErr error
	var outcome AtomicProcessResult
	attempts := 0

	for attempt := 0; attempt <= p.cfg.MaxRetries; attempt++ {
		attempts = attempt
		if attempt > 0 {
			wait := p.backoffDuration(attempt)
			select {
			case <-ctx.Done():
				lastErr = ctx.Err()
				break
			case <-time.After(wait):
			}
		}

		if hook := p.getEventHook(); hook != nil {
			if hookErr := hook(ctx, ev, attempt); hookErr != nil {
				lastErr = hookErr
				if isPermanentError(hookErr) {
					break
				}
				continue
			}
		}

		res, txErr := p.applyEventAtomically(ctx, ev)
		if txErr == nil {
			outcome = res
			lastErr = nil
			break
		}
		lastErr = txErr
		if isPermanentError(txErr) {
			break
		}
	}

	if lastErr != nil {
		p.logger.Warn("event_routed_to_dlq",
			"worker", workerID,
			"event_id", ev.EventID,
			"event_type", ev.EventType,
			"entity_id", ev.EntityID,
			"retries", attempts,
			"error", p.sanitizer.RedactSecrets(lastErr.Error()),
		)
		if err := p.routeToDLQ(ctx, ev, lastErr.Error(), attempts); err != nil {
			// DLQ commit failed: do NOT acknowledge broker entry so it remains pending for recovery!
			p.logger.Error("dlq_commit_failed_leaving_pending",
				"worker", workerID,
				"event_id", ev.EventID,
				"message_id", delivery.MessageID,
				"error", err,
			)
			p.nackDelivery(ctx, delivery, ev.EventID, err.Error())
			return
		}
		// Durable DLQ write committed: safe to acknowledge broker entry.
		p.ackDelivery(ctx, delivery, ev.EventID)
		return
	}

	// 2. Atomic transaction committed: update counters ONLY for committed outcomes, then ACK.
	if outcome.Duplicate {
		p.metrics.IncDuplicate()
		p.logger.Info("duplicate_event_skipped",
			"worker", workerID,
			"event_id", ev.EventID,
			"event_type", ev.EventType,
			"entity_id", ev.EntityID,
			"correlation_id", ev.CorrelationID,
		)
		p.ackDelivery(ctx, delivery, ev.EventID)
		return
	}

	if outcome.OutOfOrder {
		p.metrics.IncOutOfOrder()
	}
	p.metrics.IncProcessed()

	// Recompute correlated incidents and emit non-blocking operational alerts.
	if err := p.RecomputeIncidents(ctx); err != nil {
		p.logger.Error("recompute_incidents_failed", "event_id", ev.EventID, "error", err)
	}

	// Acknowledge broker message ONLY after durable state/event/claim commit completed.
	p.ackDelivery(ctx, delivery, ev.EventID)
}

func (p *Processor) backoffDuration(attempt int) time.Duration {
	exp := p.cfg.BaseBackoff * time.Duration(1<<(attempt-1))
	if exp > p.cfg.MaxBackoff {
		exp = p.cfg.MaxBackoff
	}
	jitterMax := int64(exp / 2)
	if jitterMax <= 0 {
		return exp
	}
	return exp + time.Duration(rand.Int64N(jitterMax))
}

func isPermanentError(err error) bool {
	return errors.Is(err, domain.ErrInvalidInput) || errors.Is(err, domain.ErrNotFound)
}

func (p *Processor) applyEventAtomically(ctx context.Context, ev domain.SupplyEvent) (AtomicProcessResult, error) {
	// Explicit poison marker support for resilience testing and validation.
	if poison, ok := ev.Payload["poison"].(bool); ok && poison {
		return AtomicProcessResult{}, fmt.Errorf("%w: poison payload detected", domain.ErrInvalidInput)
	}

	p.stateMu.Lock()
	defer p.stateMu.Unlock()

	return p.repo.ProcessEventAtomic(ctx, ev, func(txCtx context.Context, tx EventApplyTx) (bool, error) {
		return p.applyEventStateTx(txCtx, tx, ev)
	})
}

func (p *Processor) applyEventStateTx(ctx context.Context, tx EventApplyTx, ev domain.SupplyEvent) (outOfOrder bool, err error) {
	now := p.nowFn()

	switch ev.EventType {
	case domain.EventSupplierDisruption, domain.EventSupplierRecovered:
		sup, err := tx.GetSupplier(ctx, ev.EntityID)
		if err != nil {
			return false, err
		}
		if sup.LastOccurredAt != nil && ev.OccurredAt.Before(*sup.LastOccurredAt) {
			// Out-of-order: preserve newer state, still record in timeline.
			return true, nil
		}
		occ := ev.OccurredAt.UTC()
		sup.LastOccurredAt = &occ
		sup.UpdatedAt = now
		if ev.EventType == domain.EventSupplierDisruption {
			sev := floatFromMap(ev.Payload, "severity", 80)
			sup.ActiveDisruptionSeverity = sev
			if sev >= 50 {
				sup.Status = domain.SupplierDisrupted
			} else {
				sup.Status = domain.SupplierDegraded
			}
		} else {
			sev := floatFromMap(ev.Payload, "severity", 0)
			sup.ActiveDisruptionSeverity = sev
			sup.Status = domain.SupplierOperational
		}
		return false, tx.UpsertSupplier(ctx, *sup)

	case domain.EventRouteCongestion:
		rt, err := tx.GetRoute(ctx, ev.EntityID)
		if err != nil {
			return false, err
		}
		if rt.LastOccurredAt != nil && ev.OccurredAt.Before(*rt.LastOccurredAt) {
			return true, nil
		}
		occ := ev.OccurredAt.UTC()
		rt.LastOccurredAt = &occ
		rt.UpdatedAt = now
		sev := floatFromMap(ev.Payload, "congestion_severity", 75)
		rt.CongestionSeverity = sev
		if v, ok := numberFromMap(ev.Payload, "delay_variance_days"); ok {
			rt.TransitDelayVarianceDays = v
		}
		if w, ok := numberFromMap(ev.Payload, "weather_incidents"); ok {
			rt.WeatherIncidentCount = int(w)
		}
		blocked, _ := ev.Payload["blocked"].(bool)
		switch {
		case blocked || sev >= 90:
			rt.Status = domain.RouteBlocked
		case sev >= 35:
			rt.Status = domain.RouteCongested
		default:
			rt.Status = domain.RouteClear
		}
		return false, tx.UpsertRoute(ctx, *rt)

	case domain.EventShipmentDelayed:
		sh, err := tx.GetShipment(ctx, ev.EntityID)
		if err != nil {
			return false, err
		}
		if sh.LastOccurredAt != nil && ev.OccurredAt.Before(*sh.LastOccurredAt) {
			return true, nil
		}
		occ := ev.OccurredAt.UTC()
		sh.LastOccurredAt = &occ
		sh.UpdatedAt = now
		delayHours := floatFromMap(ev.Payload, "delay_hours", 24)
		if delayHours < 0 {
			return false, fmt.Errorf("%w: delay_hours must be non-negative", domain.ErrInvalidInput)
		}
		sh.DelayHours += delayHours
		sh.Status = domain.ShipmentDelayed
		if sh.CurrentETA.IsZero() {
			sh.CurrentETA = sh.ScheduledETA
		}
		if !sh.CurrentETA.IsZero() {
			sh.CurrentETA = sh.CurrentETA.Add(time.Duration(delayHours * float64(time.Hour)))
		}
		if ev.Notes != "" {
			sh.Notes = ev.Notes
		}
		return false, tx.UpsertShipment(ctx, *sh)

	case domain.EventInventoryConsumed, domain.EventInventoryReplenished:
		item, err := tx.GetInventoryItem(ctx, ev.EntityID)
		if err != nil {
			return false, err
		}
		if item.LastOccurredAt != nil && ev.OccurredAt.Before(*item.LastOccurredAt) {
			return true, nil
		}
		units, ok := numberFromMap(ev.Payload, "units")
		if !ok || units < 0 {
			return false, fmt.Errorf("%w: payload.units must be a non-negative number", domain.ErrInvalidInput)
		}
		occ := ev.OccurredAt.UTC()
		item.LastOccurredAt = &occ
		item.UpdatedAt = now
		if ev.EventType == domain.EventInventoryConsumed {
			item.OnHandUnits -= int(units)
			if item.OnHandUnits < 0 {
				item.OnHandUnits = 0
			}
		} else {
			item.OnHandUnits += int(units)
		}
		return false, tx.UpsertInventoryItem(ctx, *item)

	default:
		return false, fmt.Errorf("%w: unsupported event type %q", domain.ErrInvalidInput, ev.EventType)
	}
}

// RecomputeIncidents runs the deterministic RiskEngine correlation across current persistent state,
// preserves attached AI investigations, updates metrics, and dispatches alerts for HIGH/CRITICAL incidents.
func (p *Processor) RecomputeIncidents(ctx context.Context) error {
	p.stateMu.Lock()
	suppliers, err := p.repo.ListSuppliers(ctx)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	routes, err := p.repo.ListRoutes(ctx)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	shipments, err := p.repo.ListShipments(ctx)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	inventory, err := p.repo.ListInventoryItems(ctx)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	events, err := p.repo.ListSupplyEvents(ctx, 500)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	existingIncidents, err := p.repo.ListIncidents(ctx)
	if err != nil {
		p.stateMu.Unlock()
		return err
	}
	p.stateMu.Unlock()

	existingMap := make(map[string]domain.CorrelatedIncident, len(existingIncidents))
	for _, inc := range existingIncidents {
		existingMap[inc.ID] = inc
	}

	now := p.nowFn()
	computed := p.engine.Correlate(suppliers, routes, shipments, inventory, events, now)

	for i := range computed {
		if prev, ok := existingMap[computed[i].ID]; ok {
			computed[i].CreatedAt = prev.CreatedAt
			computed[i].AIInvestigation = prev.AIInvestigation
			if prev.Status == domain.IncidentInvestigating || prev.Status == domain.IncidentMitigated {
				computed[i].Status = prev.Status
			}
		}
		if err := p.repo.UpsertIncident(ctx, computed[i]); err != nil {
			return err
		}
		if computed[i].Severity == domain.SeverityHigh || computed[i].Severity == domain.SeverityCritical {
			p.dispatchAlertSafely(ctx, computed[i])
		}
	}

	p.metrics.SetActiveIncidents(len(computed))
	return nil
}

func (p *Processor) dispatchAlertSafely(ctx context.Context, inc domain.CorrelatedIncident) {
	// Deduplicate alert bursts for the same incident within a 60s window.
	p.alertedMu.Lock()
	last, seen := p.alertedAt[inc.ID]
	now := p.nowFn()
	if seen && now.Sub(last) < 60*time.Second {
		p.alertedMu.Unlock()
		return
	}
	p.alertedAt[inc.ID] = now
	p.alertedMu.Unlock()

	alert := domain.AlertNotification{
		ID:         "alt-" + uuid.NewString(),
		IncidentID: inc.ID,
		Severity:   inc.Severity,
		Title:      inc.Title,
		Message:    inc.Summary,
		Channel:    p.notifier.Channel(),
		Delivered:  true,
		CreatedAt:  now,
	}

	notifyCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := p.notifier.Notify(notifyCtx, alert); err != nil {
		alert.Delivered = false
		alert.Error = p.sanitizer.RedactSecrets(err.Error())
		p.logger.Warn("alert_delivery_degraded",
			"incident_id", inc.ID,
			"channel", alert.Channel,
			"error", alert.Error,
		)
	}
	if err := p.repo.InsertAlert(ctx, alert); err != nil {
		p.logger.Error("persist_alert_failed", "alert_id", alert.ID, "error", err)
	}
}

func (p *Processor) routeToDLQ(ctx context.Context, ev domain.SupplyEvent, reason string, retries int) error {
	raw, _ := json.Marshal(ev)
	dlq := domain.DeadLetterEvent{
		ID:          "dlq-" + uuid.NewString(),
		EventID:     ev.EventID,
		EventType:   ev.EventType,
		EntityID:    ev.EntityID,
		RawPayload:  string(raw),
		ErrorReason: p.sanitizer.RedactSecrets(reason),
		RetryCount:  retries,
		Replayed:    false,
		CreatedAt:   p.nowFn(),
	}
	if err := p.repo.RecordEventDLQAtomic(ctx, ev, dlq); err != nil {
		p.logger.Error("insert_dlq_failed", "event_id", ev.EventID, "error", err)
		return err
	}
	p.metrics.IncDLQ()
	return nil
}

func (p *Processor) routeMalformedToDLQ(ctx context.Context, d Delivery, malformedID string) error {
	now := p.nowFn()
	dlq := domain.DeadLetterEvent{
		ID:          "dlq-" + uuid.NewString(),
		EventID:     malformedID,
		EventType:   domain.EventType("MALFORMED_BROKER_MESSAGE"),
		EntityID:    "UNKNOWN",
		RawPayload:  p.sanitizer.RedactSecrets(d.RawPayload),
		ErrorReason: p.sanitizer.RedactSecrets(d.ParseErr.Error()),
		RetryCount:  0,
		Replayed:    false,
		CreatedAt:   now,
	}
	if err := p.repo.RecordEventDLQAtomic(ctx, domain.SupplyEvent{
		EventID:    malformedID,
		EventType:  dlq.EventType,
		EntityID:   dlq.EntityID,
		OccurredAt: now,
		ReceivedAt: now,
	}, dlq); err != nil {
		p.logger.Error("insert_malformed_dlq_failed", "message_id", d.MessageID, "error", err)
		return err
	}
	p.metrics.IncDLQ()
	return nil
}

// ReplayDLQ resets the DLQ claim for a dead-lettered event and re-enqueues it for processing.
func (p *Processor) ReplayDLQ(ctx context.Context, dlqID, actor, correlationID string) (*domain.DeadLetterEvent, error) {
	dlq, err := p.repo.GetDLQ(ctx, dlqID)
	if err != nil {
		return nil, err
	}
	var ev domain.SupplyEvent
	if err := json.Unmarshal([]byte(dlq.RawPayload), &ev); err != nil {
		return nil, fmt.Errorf("%w: invalid DLQ payload: %v", domain.ErrInvalidInput, err)
	}
	// Strip explicit poison flag if present on replay only when caller fixed data; otherwise keep original payload.
	ev.CorrelationID = correlationID
	ev.ReceivedAt = p.nowFn()

	if err := p.repo.ResetDLQEventClaim(ctx, ev.EventID); err != nil {
		return nil, err
	}
	if _, err := p.Enqueue(ctx, ev); err != nil {
		return nil, err
	}
	now := p.nowFn()
	if err := p.repo.MarkDLQReplayed(ctx, dlq.ID, now); err != nil {
		return nil, err
	}
	dlq.Replayed = true
	dlq.ReplayedAt = &now

	_ = p.repo.InsertAudit(ctx, domain.AuditEntry{
		ID:            "aud-" + uuid.NewString(),
		CorrelationID: correlationID,
		Actor:         actor,
		ActorType:     domain.ActorHuman,
		Action:        "DLQ_REPLAYED",
		ResourceType:  "DLQ_EVENT",
		ResourceID:    dlq.ID,
		Outcome:       "SUCCESS",
		Details:       map[string]any{"event_id": ev.EventID, "entity_id": ev.EntityID},
		CreatedAt:     now,
	})
	return dlq, nil
}

func inferEntityType(et domain.EventType) string {
	switch et {
	case domain.EventSupplierDisruption, domain.EventSupplierRecovered:
		return "SUPPLIER"
	case domain.EventRouteCongestion:
		return "ROUTE"
	case domain.EventShipmentDelayed:
		return "SHIPMENT"
	case domain.EventInventoryConsumed, domain.EventInventoryReplenished:
		return "INVENTORY"
	default:
		return "UNKNOWN"
	}
}

func numberFromMap(m map[string]any, key string) (float64, bool) {
	if m == nil {
		return 0, false
	}
	raw, ok := m[key]
	if !ok || raw == nil {
		return 0, false
	}
	switch v := raw.(type) {
	case float64:
		return v, true
	case float32:
		return float64(v), true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		f, err := v.Float64()
		return f, err == nil
	default:
		return 0, false
	}
}

func floatFromMap(m map[string]any, key string, fallback float64) float64 {
	if v, ok := numberFromMap(m, key); ok {
		return v
	}
	return fallback
}
