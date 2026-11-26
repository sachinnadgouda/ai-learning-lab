package application_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

// TestProcessor_AtomicPersistenceBoundaries injects deterministic failures at EVERY persistence
// boundary (begin_tx, check_claim, read_entity, mutate_entity, append_event, complete_claim, commit_tx)
// and proves:
//  1. Zero partial entity state or supply_events rows persist on rollback.
//  2. No dangling IN_PROGRESS or false PROCESSED claim persists on failure.
//  3. Metrics counters reflect committed outcomes only.
//  4. Failed commits remain 100% recoverable on retry/redelivery.
//  5. Duplicate redelivery after commit cannot apply the same mutation twice.
func TestProcessor_AtomicPersistenceBoundaries(t *testing.T) {
	stages := []string{
		"begin_tx",
		"check_claim",
		"read_entity",
		"mutate_entity",
		"append_event",
		"complete_claim",
		"commit_tx",
	}

	for _, stage := range stages {
		stage := stage
		t.Run("fail_at_"+stage, func(t *testing.T) {
			store, err := sqlite.Open(":memory:")
			if err != nil {
				t.Fatalf("open sqlite: %v", err)
			}
			t.Cleanup(func() { _ = store.Close() })

			ctx := context.Background()
			now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
			if err := application.SeedRepository(ctx, store, now); err != nil {
				t.Fatalf("seed repo: %v", err)
			}

			memBroker := broker.NewMemoryBroker(16)
			metrics := application.NewMetrics()
			logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

			// MaxRetries=0 so we directly observe rollback + DLQ on the injected boundary failure,
			// then test direct atomic failure & recovery without DLQ as well.
			proc := application.NewProcessor(
				application.ProcessorConfig{
					WorkerCount:    1,
					MaxRetries:     1,
					BaseBackoff:    5 * time.Millisecond,
					MaxBackoff:     10 * time.Millisecond,
					ProcessTimeout: 3 * time.Second,
				},
				memBroker,
				store,
				domain.NewRiskEngine(),
				application.NewInMemoryNotifier(),
				metrics,
				logger,
			)
			proc.SetClock(func() time.Time { return now })

			evID := "ev-fault-" + stage
			var failCount atomic.Int32

			// Fail ONLY on the first attempt (attempt 1); second retry attempt succeeds!
			store.SetFaultHook(func(s, eventID string) error {
				if eventID == evID && s == stage && failCount.Add(1) == 1 {
					return errors.New("injected failure at " + stage)
				}
				return nil
			})

			// First verify direct ProcessEventAtomic leaves zero partial state on failure.
			failCount.Store(0)
			ev := domain.SupplyEvent{
				EventID:    evID,
				EventType:  domain.EventInventoryConsumed,
				EntityID:   "SKU-MCU-01",
				EntityType: "INVENTORY",
				OccurredAt: now,
				ReceivedAt: now,
				Payload:    map[string]any{"units": 120},
			}
			_, directErr := store.ProcessEventAtomic(ctx, ev, func(txCtx context.Context, tx application.EventApplyTx) (bool, error) {
				item, err := tx.GetInventoryItem(txCtx, "SKU-MCU-01")
				if err != nil {
					return false, err
				}
				item.OnHandUnits -= 120
				return false, tx.UpsertInventoryItem(txCtx, *item)
			})
			if directErr == nil {
				t.Fatalf("expected injected error at stage %s", stage)
			}

			// Verify complete rollback: inventory still 720, supply_events empty, claim does not exist!
			itemAfterFail, err := store.GetInventoryItem(ctx, "SKU-MCU-01")
			if err != nil {
				t.Fatalf("get inventory after fail: %v", err)
			}
			if itemAfterFail.OnHandUnits != 720 {
				t.Fatalf("stage %s: partial state leaked on rollback! on_hand=%d want 720", stage, itemAfterFail.OnHandUnits)
			}
			eventsAfterFail, err := store.ListSupplyEvents(ctx, 50)
			if err != nil {
				t.Fatalf("list events after fail: %v", err)
			}
			if len(eventsAfterFail) != 0 {
				t.Fatalf("stage %s: supply_events row leaked on rollback! count=%d", stage, len(eventsAfterFail))
			}
			status, exists, err := store.GetEventClaimStatus(ctx, evID)
			if err != nil {
				t.Fatalf("get claim status: %v", err)
			}
			if exists {
				t.Fatalf("stage %s: dangling claim %q persisted after rollback", stage, status)
			}

			// Now reset fault hook so attempt 1 fails inside Processor and retry succeeds (recovery!).
			failCount.Store(0)
			wCtx, cancel := context.WithCancel(ctx)
			proc.Start(wCtx)
			defer func() {
				_ = proc.Shutdown(ctx)
				cancel()
			}()

			if _, err := proc.Enqueue(ctx, ev); err != nil {
				t.Fatalf("enqueue: %v", err)
			}
			waitUntil(t, 3*time.Second, func() bool {
				return metrics.Snapshot()["events_processed_total"] == 1
			}, "event recovered on retry after "+stage+" failure")

			// Verify committed state + event + claim all present together.
			itemAfterRecover, _ := store.GetInventoryItem(ctx, "SKU-MCU-01")
			if itemAfterRecover.OnHandUnits != 600 {
				t.Fatalf("stage %s: expected 600 after recovery, got %d", stage, itemAfterRecover.OnHandUnits)
			}
			status, exists, _ = store.GetEventClaimStatus(ctx, evID)
			if !exists || status != domain.ProcessingProcessed {
				t.Fatalf("stage %s: expected PROCESSED claim, got exists=%v status=%q", stage, exists, status)
			}
			eventsAfterRecover, _ := store.ListSupplyEvents(ctx, 50)
			if len(eventsAfterRecover) != 1 || eventsAfterRecover[0].EventID != evID {
				t.Fatalf("stage %s: expected 1 supply_events row, got %d", stage, len(eventsAfterRecover))
			}

			// Duplicate redelivery MUST NOT apply the 120-unit deduction a second time.
			if _, err := proc.Enqueue(ctx, ev); err != nil {
				t.Fatalf("enqueue duplicate redelivery: %v", err)
			}
			waitUntil(t, 3*time.Second, func() bool {
				return metrics.Snapshot()["events_duplicate_total"] == 1
			}, "duplicate redelivery skipped")

			itemAfterDup, _ := store.GetInventoryItem(ctx, "SKU-MCU-01")
			if itemAfterDup.OnHandUnits != 600 {
				t.Fatalf("stage %s: duplicate redelivery re-applied mutation! on_hand=%d want 600", stage, itemAfterDup.OnHandUnits)
			}
			if metrics.Snapshot()["events_processed_total"] != 1 {
				t.Fatalf("stage %s: expected processed counter still 1, got %d", stage, metrics.Snapshot()["events_processed_total"])
			}
		})
	}
}

type trackingBroker struct {
	ch     chan application.Delivery
	acks   atomic.Int32
	nacks  atomic.Int32
	closed atomic.Bool
}

func (b *trackingBroker) Name() string               { return "tracking" }
func (b *trackingBroker) Depth() int                 { return len(b.ch) }
func (b *trackingBroker) Capacity() int              { return cap(b.ch) }
func (b *trackingBroker) Ping(context.Context) error { return nil }
func (b *trackingBroker) Close() error {
	if b.closed.CompareAndSwap(false, true) {
		close(b.ch)
	}
	return nil
}
func (b *trackingBroker) Publish(_ context.Context, ev domain.SupplyEvent) error {
	b.ch <- application.Delivery{
		MessageID: "trk-" + ev.EventID,
		Event:     ev,
		Ack:       func(context.Context) error { b.acks.Add(1); return nil },
		Nack:      func(context.Context, string) error { b.nacks.Add(1); return nil },
	}
	return nil
}
func (b *trackingBroker) Receive(ctx context.Context) (application.Delivery, error) {
	select {
	case d, ok := <-b.ch:
		if !ok {
			return application.Delivery{}, io.EOF
		}
		return d, nil
	case <-ctx.Done():
		return application.Delivery{}, ctx.Err()
	}
}

// TestProcessor_DLQCommitFailureDoesNotAckOrIncrementCounter verifies that if DLQ persistence fails
// after processing retries are exhausted, the message is Nacked (not Acked) and metrics remain accurate.
func TestProcessor_DLQCommitFailureDoesNotAckOrIncrementCounter(t *testing.T) {
	store, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	ctx := context.Background()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	if err := application.SeedRepository(ctx, store, now); err != nil {
		t.Fatalf("seed repo: %v", err)
	}

	tb := &trackingBroker{ch: make(chan application.Delivery, 4)}
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	proc := application.NewProcessor(
		application.ProcessorConfig{WorkerCount: 1, MaxRetries: 0, ProcessTimeout: 3 * time.Second},
		tb,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc.SetClock(func() time.Time { return now })

	store.SetFaultHook(func(stage, eventID string) error {
		if eventID == "ev-dlq-fail" && (stage == "commit_tx" || stage == "dlq_insert") {
			return errors.New("injected failure at " + stage)
		}
		return nil
	})

	wCtx, cancel := context.WithCancel(ctx)
	proc.Start(wCtx)
	defer func() {
		_ = proc.Shutdown(ctx)
		cancel()
	}()

	_, err = proc.Enqueue(ctx, domain.SupplyEvent{
		EventID:    "ev-dlq-fail",
		EventType:  domain.EventInventoryConsumed,
		EntityID:   "SKU-MCU-01",
		OccurredAt: now,
		Payload:    map[string]any{"units": 25},
	})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}

	waitUntil(t, 3*time.Second, func() bool {
		return tb.nacks.Load() == 1
	}, "delivery nacked when DLQ commit fails")

	if tb.acks.Load() != 0 {
		t.Fatalf("expected 0 acks when DLQ commit failed, got %d", tb.acks.Load())
	}
	if metrics.Snapshot()["events_dlq_total"] != 0 || metrics.Snapshot()["events_processed_total"] != 0 {
		t.Fatalf("expected 0 processed and 0 dlq counters when commits failed, got %+v", metrics.Snapshot())
	}
	_, exists, _ := store.GetEventClaimStatus(ctx, "ev-dlq-fail")
	if exists {
		t.Fatal("expected no claim row committed when both main tx and DLQ tx rolled back")
	}
}
