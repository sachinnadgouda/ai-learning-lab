package broker_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

// startEphemeralRedis connects to TEST_REDIS_ADDR if provided (e.g. CI service container),
// or launches a real ephemeral redis-server process on a free loopback port.
func startEphemeralRedis(t *testing.T) string {
	t.Helper()
	if addr := os.Getenv("TEST_REDIS_ADDR"); addr != "" {
		return addr
	}

	redisBin, err := exec.LookPath("redis-server")
	if err != nil {
		t.Fatalf("redis-server binary is required for real Redis integration tests (or set TEST_REDIS_ADDR): %v", err)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("allocate free port for ephemeral redis: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()

	addr := fmt.Sprintf("127.0.0.1:%d", port)
	cmd := exec.Command(
		redisBin,
		"--bind", "127.0.0.1",
		"--port", strconv.Itoa(port),
		"--save", "",
		"--appendonly", "no",
		"--dir", t.TempDir(),
		"--loglevel", "warning",
	)
	if err := cmd.Start(); err != nil {
		t.Fatalf("start ephemeral redis-server: %v", err)
	}
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
	})

	client := redis.NewClient(&redis.Options{Addr: addr})
	defer func() { _ = client.Close() }()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if err := client.Ping(context.Background()).Err(); err == nil {
			return addr
		}
		time.Sleep(30 * time.Millisecond)
	}
	t.Fatalf("ephemeral redis-server at %s did not become ready in time", addr)
	return ""
}

func waitUntilCond(t *testing.T, timeout time.Duration, cond func() bool, desc string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for: %s", desc)
}

func openSeededStore(t *testing.T) (*sqlite.Store, time.Time) {
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
	return store, now
}

func TestRedisBroker_ReceiveDoesNotAckPrematurely(t *testing.T) {
	addr := startEphemeralRedis(t)
	ctx := context.Background()
	stream := "test-stream-" + uuid.NewString()[:8]
	group := "test-group-" + uuid.NewString()[:8]

	rb := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "consumer-a",
		ClaimMinIdle:     100 * time.Millisecond,
		RecoveryInterval: 50 * time.Millisecond,
		BlockTimeout:     100 * time.Millisecond,
	})
	defer func() { _ = rb.Close() }()

	ev := domain.SupplyEvent{
		EventID:    "ev-redis-ack-1",
		EventType:  domain.EventInventoryConsumed,
		EntityID:   "SKU-MCU-01",
		OccurredAt: time.Now().UTC(),
		ReceivedAt: time.Now().UTC(),
		Payload:    map[string]any{"units": 20},
	}
	if err := rb.Publish(ctx, ev); err != nil {
		t.Fatalf("publish: %v", err)
	}

	recvCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	delivery, err := rb.Receive(recvCtx)
	if err != nil {
		t.Fatalf("receive: %v", err)
	}
	if delivery.Event.EventID != ev.EventID {
		t.Fatalf("unexpected event_id: %s", delivery.Event.EventID)
	}

	// Critical invariant: Receive() MUST NOT have acknowledged the entry yet.
	pending, err := rb.PendingCount(ctx)
	if err != nil {
		t.Fatalf("pending count: %v", err)
	}
	if pending != 1 {
		t.Fatalf("expected 1 pending entry after Receive() before Ack(), got %d", pending)
	}

	// Only after explicit Ack() should pending drop to 0.
	if err := delivery.Ack(ctx); err != nil {
		t.Fatalf("ack: %v", err)
	}
	pending, err = rb.PendingCount(ctx)
	if err != nil {
		t.Fatalf("pending count after ack: %v", err)
	}
	if pending != 0 {
		t.Fatalf("expected 0 pending entries after Ack(), got %d", pending)
	}
}

func TestRedisBroker_CrashRecoveryViaXAutoClaimAndFailedProcessingRecovery(t *testing.T) {
	addr := startEphemeralRedis(t)
	ctx := context.Background()
	stream := "test-crash-" + uuid.NewString()[:8]
	group := "test-group-" + uuid.NewString()[:8]

	// 1. Consumer 1 receives an event and crashes BEFORE committing or acknowledging.
	crashedBroker := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "crashed-worker-1",
		ClaimMinIdle:     80 * time.Millisecond,
		RecoveryInterval: 40 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})
	ev := domain.SupplyEvent{
		EventID:    "ev-redis-crash-recover-1",
		EventType:  domain.EventInventoryConsumed,
		EntityID:   "SKU-MCU-01",
		OccurredAt: time.Date(2026, 11, 26, 11, 0, 0, 0, time.UTC),
		ReceivedAt: time.Date(2026, 11, 26, 11, 0, 0, 0, time.UTC),
		Payload:    map[string]any{"units": 50},
	}
	if err := crashedBroker.Publish(ctx, ev); err != nil {
		t.Fatalf("publish: %v", err)
	}
	d, err := crashedBroker.Receive(ctx)
	if err != nil || d.Event.EventID != ev.EventID {
		t.Fatalf("crashed worker receive failed: %+v err=%v", d, err)
	}
	// Simulate sudden process crash: do NOT call d.Ack; abort broker immediately.
	// Call Nack only to release local waitgroup without ACKing in Redis, simulating process termination.
	_ = d.Nack(ctx, "simulated process crash")
	_ = crashedBroker.Close()

	// Wait past MinIdle so entry is eligible for XAUTOCLAIM.
	time.Sleep(120 * time.Millisecond)

	// 2. Consumer 2 boots with Processor + SQLite and recovers the orphaned pending entry.
	store, now := openSeededStore(t)
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	// First fail DLQ commit on a second event? Let's test recovery of the crashed event first!
	recoveryBroker := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "recovery-worker-2",
		ClaimMinIdle:     80 * time.Millisecond,
		RecoveryInterval: 40 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})
	proc := application.NewProcessor(
		application.ProcessorConfig{
			WorkerCount:    1,
			MaxRetries:     1,
			BaseBackoff:    10 * time.Millisecond,
			MaxBackoff:     30 * time.Millisecond,
			ProcessTimeout: 3 * time.Second,
		},
		recoveryBroker,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc.SetClock(func() time.Time { return now })

	// Inject a failure on BOTH processing AND DLQ commit on first delivery so Redis entry stays unacked,
	// then clear the fault so the second delivery recovers cleanly!
	var firstFail atomic.Bool
	firstFail.Store(true)
	store.SetFaultHook(func(stage, eventID string) error {
		if eventID == ev.EventID && firstFail.Load() && (stage == "commit_tx" || stage == "dlq_commit") {
			return errors.New("simulated transient sqlite outage")
		}
		return nil
	})

	wCtx, cancel := context.WithCancel(ctx)
	proc.Start(wCtx)

	// Give recovery worker time to hit the simulated DB outage and Nack (leaving message pending in Redis).
	time.Sleep(300 * time.Millisecond)
	pendingDuringOutage, err := recoveryBroker.PendingCount(ctx)
	if err != nil {
		t.Fatalf("pending count during outage: %v", err)
	}
	if pendingDuringOutage != 1 {
		t.Fatalf("expected pending=1 when both processing and DLQ commit failed, got %d", pendingDuringOutage)
	}
	if metrics.Snapshot()["events_processed_total"] != 0 || metrics.Snapshot()["events_dlq_total"] != 0 {
		t.Fatalf("counters must remain 0 when commits failed: %+v", metrics.Snapshot())
	}

	// Clear DB fault -> consumer 3 (or same consumer after MinIdle) recovers and processes cleanly!
	firstFail.Store(false)
	_ = proc.Shutdown(ctx)
	cancel()

	time.Sleep(120 * time.Millisecond)

	recoveryBroker2 := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "recovery-worker-3",
		ClaimMinIdle:     80 * time.Millisecond,
		RecoveryInterval: 40 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})
	proc2 := application.NewProcessor(
		application.ProcessorConfig{
			WorkerCount:    1,
			MaxRetries:     1,
			BaseBackoff:    10 * time.Millisecond,
			MaxBackoff:     30 * time.Millisecond,
			ProcessTimeout: 3 * time.Second,
		},
		recoveryBroker2,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc2.SetClock(func() time.Time { return now })
	wCtx2, cancel2 := context.WithCancel(ctx)
	proc2.Start(wCtx2)
	defer func() {
		_ = proc2.Shutdown(ctx)
		cancel2()
	}()

	waitUntilCond(t, 4*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 1
	}, "recovered event committed cleanly after outage")

	waitUntilCond(t, 2*time.Second, func() bool {
		p, err := recoveryBroker2.PendingCount(ctx)
		return err == nil && p == 0
	}, "redis pending count drained to 0 after commit")

	item, err := store.GetInventoryItem(ctx, "SKU-MCU-01")
	if err != nil {
		t.Fatalf("get inventory: %v", err)
	}
	// Initial 720 - 50 = 670 applied exactly once!
	if item.OnHandUnits != 670 {
		t.Fatalf("expected OnHandUnits=670 after crash+outage recovery, got %d", item.OnHandUnits)
	}
}

func TestRedisBroker_MalformedMessageRoutedToDLQNotSilentlyDiscarded(t *testing.T) {
	addr := startEphemeralRedis(t)
	ctx := context.Background()
	stream := "test-malformed-" + uuid.NewString()[:8]
	group := "test-group-" + uuid.NewString()[:8]

	store, now := openSeededStore(t)
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	rb := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "worker-malformed",
		ClaimMinIdle:     100 * time.Millisecond,
		RecoveryInterval: 50 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})

	// Ensure group exists, then directly XADD a malformed JSON payload and a missing-payload entry.
	if err := rb.Ping(ctx); err != nil {
		t.Fatalf("ping: %v", err)
	}
	rawClient := redis.NewClient(&redis.Options{Addr: addr})
	defer func() { _ = rawClient.Close() }()
	_ = rawClient.XGroupCreateMkStream(ctx, stream, group, "0").Err()

	if err := rawClient.XAdd(ctx, &redis.XAddArgs{
		Stream: stream,
		Values: map[string]any{"payload": "{not-valid-json"},
	}).Err(); err != nil {
		t.Fatalf("xadd bad json: %v", err)
	}
	if err := rawClient.XAdd(ctx, &redis.XAddArgs{
		Stream: stream,
		Values: map[string]any{"unexpected_field": "abc"},
	}).Err(); err != nil {
		t.Fatalf("xadd missing payload: %v", err)
	}

	proc := application.NewProcessor(
		application.ProcessorConfig{WorkerCount: 1, MaxRetries: 0, ProcessTimeout: 3 * time.Second},
		rb,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc.SetClock(func() time.Time { return now })

	wCtx, cancel := context.WithCancel(ctx)
	proc.Start(wCtx)
	defer func() {
		_ = proc.Shutdown(ctx)
		cancel()
	}()

	waitUntilCond(t, 4*time.Second, func() bool {
		return metrics.Snapshot()["events_dlq_total"] == 2
	}, "both malformed redis messages persisted to DLQ")

	dlqItems, err := store.ListDLQ(ctx)
	if err != nil || len(dlqItems) != 2 {
		t.Fatalf("expected 2 DLQ entries for malformed messages, got len=%d err=%v", len(dlqItems), err)
	}

	waitUntilCond(t, 2*time.Second, func() bool {
		p, err := rb.PendingCount(ctx)
		return err == nil && p == 0
	}, "malformed messages acked only after DLQ persistence")
}

func TestRedisBroker_GracefulShutdownPreservesInFlightAndPendingMessages(t *testing.T) {
	addr := startEphemeralRedis(t)
	ctx := context.Background()
	stream := "test-shutdown-" + uuid.NewString()[:8]
	group := "test-group-" + uuid.NewString()[:8]

	store, now := openSeededStore(t)
	metrics := application.NewMetrics()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	rb1 := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "worker-shutdown-1",
		ClaimMinIdle:     80 * time.Millisecond,
		RecoveryInterval: 40 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})

	inFlightStarted := make(chan struct{}, 1)
	releaseInFlight := make(chan struct{})

	proc1 := application.NewProcessor(
		application.ProcessorConfig{WorkerCount: 1, MaxRetries: 0, ProcessTimeout: 3 * time.Second},
		rb1,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc1.SetClock(func() time.Time { return now })
	proc1.SetEventHook(func(_ context.Context, ev domain.SupplyEvent, _ int) error {
		if ev.EventID == "ev-shut-1" {
			select {
			case inFlightStarted <- struct{}{}:
			default:
			}
			<-releaseInFlight
		}
		return nil
	})

	// Publish 2 events before starting worker.
	for i, id := range []string{"ev-shut-1", "ev-shut-2"} {
		if err := rb1.Publish(ctx, domain.SupplyEvent{
			EventID:    id,
			EventType:  domain.EventInventoryConsumed,
			EntityID:   "SKU-MCU-01",
			OccurredAt: now.Add(time.Duration(i) * time.Minute),
			ReceivedAt: now,
			Payload:    map[string]any{"units": 10},
		}); err != nil {
			t.Fatalf("publish %s: %v", id, err)
		}
	}

	wCtx1, cancel1 := context.WithCancel(ctx)
	proc1.Start(wCtx1)

	// Wait until worker 1 has started processing ev-shut-1, then trigger graceful shutdown.
	select {
	case <-inFlightStarted:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for ev-shut-1 to start")
	}

	shutdownDone := make(chan error, 1)
	go func() {
		shutCtx, sc := context.WithTimeout(ctx, 4*time.Second)
		defer sc()
		shutdownDone <- proc1.Shutdown(shutCtx)
	}()

	// Give Shutdown a moment to call StopReceiving, then release the in-flight worker.
	time.Sleep(60 * time.Millisecond)
	close(releaseInFlight)

	if err := <-shutdownDone; err != nil {
		t.Fatalf("graceful shutdown failed: %v", err)
	}
	cancel1()

	// ev-shut-1 MUST have finished & acked; ev-shut-2 must NOT have been lost!
	if metrics.Snapshot()["events_processed_total"] < 1 {
		t.Fatalf("expected in-flight ev-shut-1 processed during graceful shutdown, got %+v", metrics.Snapshot())
	}

	// Start worker 2 in the same consumer group to verify any remaining unpulled/pending event is processed.
	rb2 := broker.NewRedisBrokerWithOptions(addr, stream, group, 32, broker.RedisBrokerOptions{
		ConsumerName:     "worker-shutdown-2",
		ClaimMinIdle:     80 * time.Millisecond,
		RecoveryInterval: 40 * time.Millisecond,
		BlockTimeout:     80 * time.Millisecond,
	})
	proc2 := application.NewProcessor(
		application.ProcessorConfig{WorkerCount: 1, MaxRetries: 0, ProcessTimeout: 3 * time.Second},
		rb2,
		store,
		domain.NewRiskEngine(),
		application.NewInMemoryNotifier(),
		metrics,
		logger,
	)
	proc2.SetClock(func() time.Time { return now })
	wCtx2, cancel2 := context.WithCancel(ctx)
	proc2.Start(wCtx2)
	defer func() {
		_ = proc2.Shutdown(ctx)
		cancel2()
	}()

	waitUntilCond(t, 4*time.Second, func() bool {
		return metrics.Snapshot()["events_processed_total"] == 2
	}, "both events processed across graceful shutdown without loss")

	item, err := store.GetInventoryItem(ctx, "SKU-MCU-01")
	if err != nil {
		t.Fatalf("get inventory: %v", err)
	}
	// Initial 720 - 10 - 10 = 700
	if item.OnHandUnits != 700 {
		t.Fatalf("expected OnHandUnits=700, got %d", item.OnHandUnits)
	}
}
