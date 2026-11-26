package application

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"sync"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// LogNotifier emits operational alerts as structured JSON log entries.
type LogNotifier struct {
	logger *slog.Logger
}

func NewLogNotifier(logger *slog.Logger) *LogNotifier {
	if logger == nil {
		logger = slog.Default()
	}
	return &LogNotifier{logger: logger}
}

func (n *LogNotifier) Channel() string { return "log" }

func (n *LogNotifier) Notify(_ context.Context, alert domain.AlertNotification) error {
	n.logger.Warn("operational_alert",
		"alert_id", alert.ID,
		"incident_id", alert.IncidentID,
		"severity", alert.Severity,
		"title", alert.Title,
		"message", alert.Message,
	)
	return nil
}

// WebhookNotifier sends alerts to an HTTP webhook with bounded timeout and retry backoff.
type WebhookNotifier struct {
	url         string
	client      *http.Client
	maxRetries  int
	baseBackoff time.Duration
}

func NewWebhookNotifier(url string, timeout time.Duration, maxRetries int) *WebhookNotifier {
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	if maxRetries < 0 {
		maxRetries = 0
	}
	return &WebhookNotifier{
		url:         url,
		client:      &http.Client{Timeout: timeout},
		maxRetries:  maxRetries,
		baseBackoff: 100 * time.Millisecond,
	}
}

func (n *WebhookNotifier) Channel() string { return "webhook" }

func (n *WebhookNotifier) Notify(ctx context.Context, alert domain.AlertNotification) error {
	body, err := json.Marshal(alert)
	if err != nil {
		return err
	}
	var lastErr error
	for attempt := 0; attempt <= n.maxRetries; attempt++ {
		if attempt > 0 {
			jitter := time.Duration(rand.Int64N(int64(n.baseBackoff)))
			wait := n.baseBackoff*time.Duration(1<<(attempt-1)) + jitter
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(wait):
			}
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.url, bytes.NewReader(body))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := n.client.Do(req)
		if err != nil {
			lastErr = fmt.Errorf("%w: webhook delivery failed: %v", domain.ErrUpstreamUnavailable, err)
			continue
		}
		_ = resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return nil
		}
		lastErr = fmt.Errorf("%w: webhook returned HTTP %d", domain.ErrUpstreamUnavailable, resp.StatusCode)
		if resp.StatusCode >= 400 && resp.StatusCode < 500 && resp.StatusCode != http.StatusTooManyRequests {
			return lastErr
		}
	}
	return lastErr
}

// InMemoryNotifier stores alerts in memory for tests.
type InMemoryNotifier struct {
	mu      sync.Mutex
	Alerts  []domain.AlertNotification
	FailErr error
}

func NewInMemoryNotifier() *InMemoryNotifier {
	return &InMemoryNotifier{}
}

func (n *InMemoryNotifier) Channel() string { return "memory" }

func (n *InMemoryNotifier) Notify(_ context.Context, alert domain.AlertNotification) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.FailErr != nil {
		return n.FailErr
	}
	n.Alerts = append(n.Alerts, alert)
	return nil
}

func (n *InMemoryNotifier) Snapshot() []domain.AlertNotification {
	n.mu.Lock()
	defer n.mu.Unlock()
	out := make([]domain.AlertNotification, len(n.Alerts))
	copy(out, n.Alerts)
	return out
}
