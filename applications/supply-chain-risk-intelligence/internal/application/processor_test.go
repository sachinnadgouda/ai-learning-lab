package application_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

func setupTestProcessor(t *testing.T, queueCap, workers, maxRetries int) (*application.Processor, *sqlite.Store, *broker.MemoryBroker, *application.Metrics, *application.InMemoryNotifier) {
	t.Helper()
	store, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	if err := application.SeedRepository(context.Background(), store, now); err != nil {
		t.Fatalf("seed repo: %v", err)
	}

	memBroker := broker.NewMemoryBroker(queueCap)
	metrics := application.NewMetrics()
	notifier := application.NewInMemoryNotifier()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	cfg := application.ProcessorConfig{
		WorkerCount:    workers,
		MaxRetries:     maxRetries,
		BaseBackoff:    10 * time.Millisecond,
		MaxBackoff:     50 * time.Millisecond,
		ProcessTimeout: 3 * time.Second,
	}
	proc := application.NewProcessor(cfg, memBroker, store, domain.NewRiskEngine(), notifier, metrics, logger)
	proc.SetClock(func() time.Time { return now })
	return proc, store, memBroker, metrics, notifier
}

func waitUntil(t *testing.T, timeout time.Duration, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(15 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for condition: %s", msg)
}

func TestProcessor_ConcurrentDuplicateIdempotency(t *testing.T) {
	proc, store, _, metrics, _ := setupTestProcessor(t, 64, 4, 2)
	ctx, cancel := context.WithCancel(context.Background())
	proc.Start(ctx)
	defer func() {
		_ = proc.Shutdown(context.Background())
		cancel()
	}()

	// Initial on-hand for SKU-MCU-01 is 720. Submit the SAME event_id 12 times concurrently!
	const goroutines = 12
	var wg sync.WaitGroup
	occ := time.Date(2026, 11, 26, 11, 0, 0, 0, time.UTC)

	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := proc.Enqueue(ctx, domain.SupplyEvent{
				EventID:    "ev-dup-concurrent-1",
				EventType:  domain.EventInventoryConsumed,
				EntityID:   "SKU-MCU-01",
				OccurredAt: occ,
				Payload:    map[string]any{"units": 100},
				Notes:      "Concurrent duplicate draw",
			})
			if err != nil {
				t.Errorf("enqueue failed: %v", err)
			}
		}()
	}
	wg.Wait()

	waitUntil(t, 3*time.Second, func() bool {
		snap := metrics.Snapshot()
		return snap["events_processed_total"]+snap["events_duplicate_total"] == goroutines
	}, "all 12 duplicate submissions handled")

	snap := metrics.Snapshot()
	if snap["events_processed_total"] != 1 {
		t.Fatalf("expected exactly 1 processed event, got %d", snap["events_processed_total"])
	}
	if snap["events_duplicate_total"] != goroutines-1 {
		t.Fatalf("expected %d duplicates skipped, got %d", goroutines-1, snap["events_duplicate_total"])
	}

	item, err := store.GetInventoryItem(ctx, "SKU-MCU-01")
	if err != nil {
		t.Fatalf("get inventory: %v", err)
	}
	if item.OnHandUnits != 620 {
		t.Fatalf("expected on-hand 620 (720 - 100 once), got %d", item.OnHandUnits)
	}
}

func TestProcessor_OutOfOrderDoesNotOverwriteNewerState(t *testing.T) {
	proc, store, _, metrics, _ := setupTestProcessor(t, 32, 2, 1)
	ctx, cancel := context.WithCancel(context.Background())
	proc.Start(ctx)
	defer func() {
		_ = proc.Shutdown(context.Background())
		cancel()
	}()

	tNew := time.Date(2026, 11, 26, 11, 30, 0, 0, time.UTC)
	tOld := time.Date(2026, 11, 26, 9, 0, 0, 0, time.UTC)

	// 1. Newer disruption event arrives first.
	_, err := proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:    "ev-sup-newer",
		EventType:  domain.EventSupplierDisruption,
		EntityID:   "SUP-TW-SEMI",
		OccurredAt: tNew,
		Payload:    map[string]any{"severity": 92},
		Notes:      "Grid failure halted wafer fab",
	})
	if err != nil {
		t.Fatalf("enqueue newer: %v", err)
	}

	waitUntil(t, 3*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 1
	}, "newer event processed")

	// 2. Older recovery event arrives late (out of order).
	_, err = proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:    "ev-sup-older-ooo",
		EventType:  domain.EventSupplierRecovered,
		EntityID:   "SUP-TW-SEMI",
		OccurredAt: tOld,
		Payload:    map[string]any{"severity": 0},
		Notes:      "Delayed recovery telemetry from earlier check",
	})
	if err != nil {
		t.Fatalf("enqueue older: %v", err)
	}

	waitUntil(t, 3*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 2
	}, "out-of-order event recorded")

	// Supplier must remain DISRUPTED with severity 92!
	sup, err := store.GetSupplier(ctx, "SUP-TW-SEMI")
	if err != nil {
		t.Fatalf("get supplier: %v", err)
	}
	if sup.Status != domain.SupplierDisrupted || sup.ActiveDisruptionSeverity != 92 {
		t.Fatalf("expected newer DISRUPTED state (92) preserved, got status=%s sev=%.1f", sup.Status, sup.ActiveDisruptionSeverity)
	}

	if metrics.Snapshot()["events_out_of_order_total"] != 1 {
		t.Fatalf("expected 1 out-of-order metric, got %d", metrics.Snapshot()["events_out_of_order_total"])
	}

	// Timeline must contain both events sorted chronologically (tOld before tNew).
	inc, err := store.GetIncident(ctx, "inc-sup-tw-semi")
	if err != nil {
		t.Fatalf("get incident: %v", err)
	}
	if len(inc.Timeline) != 2 {
		t.Fatalf("expected 2 timeline events, got %d", len(inc.Timeline))
	}
	if inc.Timeline[0].EventID != "ev-sup-older-ooo" || !inc.Timeline[0].OutOfOrder {
		t.Fatalf("expected older out-of-order event first in chronological timeline, got %+v", inc.Timeline[0])
	}
	if inc.Timeline[1].EventID != "ev-sup-newer" {
		t.Fatalf("expected newer event second in timeline, got %+v", inc.Timeline[1])
	}
}

func TestProcessor_RetryBackoffAndDLQReplay(t *testing.T) {
	proc, store, _, metrics, _ := setupTestProcessor(t, 32, 2, 2)

	var transientAttempts atomic.Int32
	var poisonFixed atomic.Bool

	proc.SetEventHook(func(_ context.Context, ev domain.SupplyEvent, attempt int) error {
		if ev.EventID == "ev-transient-ok" {
			if transientAttempts.Add(1) <= 2 {
				return domain.ErrUpstreamUnavailable
			}
			return nil
		}
		if ev.EventID == "ev-poison-dlq" && !poisonFixed.Load() {
			return domain.ErrUpstreamUnavailable
		}
		return nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	proc.Start(ctx)
	defer func() {
		_ = proc.Shutdown(context.Background())
		cancel()
	}()

	// 1. Transient failure succeeds on retry #2.
	_, err := proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:    "ev-transient-ok",
		EventType:  domain.EventInventoryConsumed,
		EntityID:   "SKU-CELL-03",
		OccurredAt: time.Date(2026, 11, 26, 11, 0, 0, 0, time.UTC),
		Payload:    map[string]any{"units": 50},
	})
	if err != nil {
		t.Fatalf("enqueue transient: %v", err)
	}

	// 2. Persistent failure exhausts retries and lands in DLQ.
	_, err = proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:    "ev-poison-dlq",
		EventType:  domain.EventInventoryConsumed,
		EntityID:   "SKU-CELL-03",
		OccurredAt: time.Date(2026, 11, 26, 11, 5, 0, 0, time.UTC),
		Payload:    map[string]any{"units": 75},
	})
	if err != nil {
		t.Fatalf("enqueue poison: %v", err)
	}

	waitUntil(t, 3*time.Second, func() bool {
		snap := metrics.Snapshot()
		return snap["events_processed_total"] == 1 && snap["events_dlq_total"] == 1
	}, "transient event succeeded and poison event routed to DLQ")

	if transientAttempts.Load() != 3 {
		t.Fatalf("expected 3 attempts (initial + 2 retries) for transient event, got %d", transientAttempts.Load())
	}

	dlqList, err := store.ListDLQ(ctx)
	if err != nil || len(dlqList) != 1 {
		t.Fatalf("expected 1 DLQ record, got len=%d err=%v", len(dlqList), err)
	}
	if dlqList[0].EventID != "ev-poison-dlq" || dlqList[0].Replayed {
		t.Fatalf("unexpected DLQ record: %+v", dlqList[0])
	}

	// 3. Fix upstream fault and replay DLQ record -> must succeed!
	poisonFixed.Store(true)
	replayed, err := proc.ReplayDLQ(ctx, dlqList[0].ID, "ops@corp", "corr-replay-1")
	if err != nil {
		t.Fatalf("replay DLQ: %v", err)
	}
	if !replayed.Replayed {
		t.Fatal("expected replayed=true")
	}

	waitUntil(t, 3*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 2
	}, "replayed DLQ event processed cleanly")

	item, err := store.GetInventoryItem(ctx, "SKU-CELL-03")
	if err != nil {
		t.Fatalf("get SKU-CELL-03: %v", err)
	}
	// Initial 1800 - 50 - 75 = 1675
	if item.OnHandUnits != 1675 {
		t.Fatalf("expected SKU-CELL-03 on_hand=1675 after transient+replay, got %d", item.OnHandUnits)
	}
}

func TestProcessor_BoundedQueueBackpressureAndGracefulShutdown(t *testing.T) {
	// Queue capacity = 2, do NOT start workers yet so queue fills deterministically.
	proc, _, _, metrics, _ := setupTestProcessor(t, 2, 2, 0)
	ctx := context.Background()

	for i := 0; i < 2; i++ {
		_, err := proc.Enqueue(ctx, domain.SupplyEvent{
			EventID:   "ev-bp-" + string(rune('a'+i)),
			EventType: domain.EventInventoryConsumed,
			EntityID:  "SKU-CTRL-04",
			Payload:   map[string]any{"units": 10},
		})
		if err != nil {
			t.Fatalf("expected enqueue %d to succeed, got %v", i, err)
		}
	}

	// Third enqueue must immediately fail with ErrQueueFull (explicit backpressure).
	_, err := proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:   "ev-bp-overflow",
		EventType: domain.EventInventoryConsumed,
		EntityID:  "SKU-CTRL-04",
		Payload:   map[string]any{"units": 10},
	})
	if !errors.Is(err, domain.ErrQueueFull) {
		t.Fatalf("expected ErrQueueFull when queue saturated, got %v", err)
	}

	// Start workers and immediately initiate graceful shutdown -> buffered 2 events must drain cleanly!
	wCtx, cancel := context.WithCancel(ctx)
	proc.Start(wCtx)

	shutCtx, shutCancel := context.WithTimeout(ctx, 3*time.Second)
	defer shutCancel()
	if err := proc.Shutdown(shutCtx); err != nil {
		t.Fatalf("graceful shutdown failed: %v", err)
	}
	cancel()

	if metrics.Snapshot()["events_processed_total"] != 2 {
		t.Fatalf("expected graceful shutdown to drain both buffered events, processed=%d", metrics.Snapshot()["events_processed_total"])
	}
}

func TestProcessor_UnavailableNotifierDegradesGracefully(t *testing.T) {
	proc, store, _, metrics, notifier := setupTestProcessor(t, 16, 2, 0)
	notifier.FailErr = domain.ErrUpstreamUnavailable

	ctx, cancel := context.WithCancel(context.Background())
	proc.Start(ctx)
	defer func() {
		_ = proc.Shutdown(context.Background())
		cancel()
	}()

	_, err := proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:   "ev-notify-fail",
		EventType: domain.EventSupplierDisruption,
		EntityID:  "SUP-TW-SEMI",
		Payload:   map[string]any{"severity": 95},
	})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}

	waitUntil(t, 3*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 1
	}, "event processed despite failing notifier")

	alerts, err := store.ListAlerts(ctx, 10)
	if err != nil || len(alerts) == 0 {
		t.Fatalf("expected degraded alert record persisted, got len=%d err=%v", len(alerts), err)
	}
	if alerts[0].Delivered {
		t.Fatal("expected Delivered=false when notifier failed")
	}
	if alerts[0].Error == "" {
		t.Fatal("expected alert error message recorded")
	}
}
