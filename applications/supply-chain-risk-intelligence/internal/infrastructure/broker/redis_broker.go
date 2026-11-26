package broker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// RedisBrokerOptions configures Redis Streams consumer identity and pending-entry recovery.
type RedisBrokerOptions struct {
	ConsumerName     string
	ClaimMinIdle     time.Duration
	RecoveryInterval time.Duration
	BlockTimeout     time.Duration
}

// RedisBroker is an optional Redis Streams EventBroker adapter with post-commit ACK,
// pending entry crash recovery (PEL self-drain + XAutoClaim), and non-destructive malformed message delivery.
type RedisBroker struct {
	client           *redis.Client
	stream           string
	group            string
	consumer         string
	capacity         int
	claimMinIdle     time.Duration
	recoveryInterval time.Duration
	blockTimeout     time.Duration

	depth      atomic.Int64
	groupReady atomic.Bool

	mu                sync.Mutex
	stopped           bool
	closed            bool
	stopCh            chan struct{}
	ownPendingDrained bool
	ownPendingCursor  string
	lastAutoClaimAt   time.Time
	autoClaimCursor   string
	inflight          sync.WaitGroup
}

func NewRedisBroker(addr, stream, group string, capacity int) *RedisBroker {
	return NewRedisBrokerWithOptions(addr, stream, group, capacity, RedisBrokerOptions{})
}

func NewRedisBrokerWithOptions(addr, stream, group string, capacity int, opts RedisBrokerOptions) *RedisBroker {
	if stream == "" {
		stream = "supply-chain-events"
	}
	if group == "" {
		group = "risk-workers"
	}
	if capacity <= 0 {
		capacity = 256
	}
	consumer := strings.TrimSpace(opts.ConsumerName)
	if consumer == "" {
		consumer = "worker-" + uuid.NewString()[:8]
	}
	claimMinIdle := opts.ClaimMinIdle
	if claimMinIdle <= 0 {
		claimMinIdle = 5 * time.Second
	}
	recoveryInterval := opts.RecoveryInterval
	if recoveryInterval <= 0 {
		recoveryInterval = 1 * time.Second
	}
	blockTimeout := opts.BlockTimeout
	if blockTimeout <= 0 {
		blockTimeout = 250 * time.Millisecond
	}

	client := redis.NewClient(&redis.Options{
		Addr:         addr,
		DialTimeout:  2 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 2 * time.Second,
	})
	return &RedisBroker{
		client:           client,
		stream:           stream,
		group:            group,
		consumer:         consumer,
		capacity:         capacity,
		claimMinIdle:     claimMinIdle,
		recoveryInterval: recoveryInterval,
		blockTimeout:     blockTimeout,
		stopCh:           make(chan struct{}),
		ownPendingCursor: "0",
		autoClaimCursor:  "0-0",
	}
}

func (b *RedisBroker) Name() string { return "redis" }

func (b *RedisBroker) ConsumerName() string { return b.consumer }

func (b *RedisBroker) ensureGroup(ctx context.Context) error {
	if b.groupReady.Load() {
		return nil
	}
	err := b.client.XGroupCreateMkStream(ctx, b.stream, b.group, "0").Err()
	if err != nil && !strings.Contains(err.Error(), "BUSYGROUP") {
		return err
	}
	b.groupReady.Store(true)
	return nil
}

func (b *RedisBroker) isStopped() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.stopped || b.closed
}

func (b *RedisBroker) Publish(ctx context.Context, ev domain.SupplyEvent) error {
	if b.isStopped() {
		return domain.ErrUpstreamUnavailable
	}

	if err := b.ensureGroup(ctx); err != nil {
		return fmt.Errorf("%w: redis stream group setup: %v", domain.ErrUpstreamUnavailable, err)
	}

	pending, err := b.client.XPending(ctx, b.stream, b.group).Result()
	if err == nil && int(pending.Count) >= b.capacity {
		b.depth.Store(pending.Count)
		return domain.ErrQueueFull
	}

	raw, err := json.Marshal(ev)
	if err != nil {
		return err
	}
	_, err = b.client.XAdd(ctx, &redis.XAddArgs{
		Stream: b.stream,
		MaxLen: int64(b.capacity * 4),
		Approx: true,
		Values: map[string]any{"payload": string(raw)},
	}).Result()
	if err != nil {
		return fmt.Errorf("%w: redis xadd failed: %v", domain.ErrUpstreamUnavailable, err)
	}
	b.depth.Add(1)
	return nil
}

// Receive delivers the next pending or new Redis Stream entry WITHOUT acknowledging it.
// Callers MUST invoke delivery.Ack(ctx) only after durable processing or DLQ persistence,
// or delivery.Nack(ctx, reason) on unrecovered failure so the message stays in the PEL.
func (b *RedisBroker) Receive(ctx context.Context) (application.Delivery, error) {
	for {
		if b.isStopped() {
			return application.Delivery{}, io.EOF
		}

		select {
		case <-ctx.Done():
			return application.Delivery{}, ctx.Err()
		case <-b.stopCh:
			return application.Delivery{}, io.EOF
		default:
		}

		if err := b.ensureGroup(ctx); err != nil {
			select {
			case <-ctx.Done():
				return application.Delivery{}, ctx.Err()
			case <-b.stopCh:
				return application.Delivery{}, io.EOF
			case <-time.After(150 * time.Millisecond):
				continue
			}
		}

		// 1. On startup, recover this consumer's own unacknowledged pending entries (PEL).
		if msg, ok, err := b.readOwnPending(ctx); err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return application.Delivery{}, err
			}
		} else if ok {
			return b.buildDelivery(msg), nil
		}

		// 2. Periodically claim stale pending entries orphaned by crashed consumers via XAUTOCLAIM.
		if msg, ok, err := b.tryAutoClaim(ctx); err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return application.Delivery{}, err
			}
		} else if ok {
			return b.buildDelivery(msg), nil
		}

		// 3. Read newly published stream entries using ">".
		streams, err := b.client.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    b.group,
			Consumer: b.consumer,
			Streams:  []string{b.stream, ">"},
			Count:    1,
			Block:    b.blockTimeout,
		}).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) {
				continue
			}
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return application.Delivery{}, err
			}
			if b.isStopped() {
				return application.Delivery{}, io.EOF
			}
			select {
			case <-ctx.Done():
				return application.Delivery{}, ctx.Err()
			case <-b.stopCh:
				return application.Delivery{}, io.EOF
			case <-time.After(150 * time.Millisecond):
				continue
			}
		}

		for _, s := range streams {
			for _, msg := range s.Messages {
				return b.buildDelivery(msg), nil
			}
		}
	}
}

func (b *RedisBroker) readOwnPending(ctx context.Context) (redis.XMessage, bool, error) {
	b.mu.Lock()
	if b.ownPendingDrained {
		b.mu.Unlock()
		return redis.XMessage{}, false, nil
	}
	cursor := b.ownPendingCursor
	b.mu.Unlock()

	streams, err := b.client.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group:    b.group,
		Consumer: b.consumer,
		Streams:  []string{b.stream, cursor},
		Count:    1,
	}).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			b.mu.Lock()
			b.ownPendingDrained = true
			b.mu.Unlock()
			return redis.XMessage{}, false, nil
		}
		return redis.XMessage{}, false, err
	}

	for _, s := range streams {
		if len(s.Messages) > 0 {
			msg := s.Messages[0]
			b.mu.Lock()
			b.ownPendingCursor = msg.ID
			b.mu.Unlock()
			return msg, true, nil
		}
	}

	b.mu.Lock()
	b.ownPendingDrained = true
	b.mu.Unlock()
	return redis.XMessage{}, false, nil
}

func (b *RedisBroker) tryAutoClaim(ctx context.Context) (redis.XMessage, bool, error) {
	now := time.Now()
	b.mu.Lock()
	if !b.lastAutoClaimAt.IsZero() && now.Sub(b.lastAutoClaimAt) < b.recoveryInterval {
		b.mu.Unlock()
		return redis.XMessage{}, false, nil
	}
	start := b.autoClaimCursor
	b.lastAutoClaimAt = now
	b.mu.Unlock()

	msgs, nextStart, err := b.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{
		Stream:   b.stream,
		Group:    b.group,
		Consumer: b.consumer,
		MinIdle:  b.claimMinIdle,
		Start:    start,
		Count:    1,
	}).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return redis.XMessage{}, false, nil
		}
		return redis.XMessage{}, false, err
	}

	b.mu.Lock()
	if nextStart == "" {
		nextStart = "0-0"
	}
	b.autoClaimCursor = nextStart
	// If we claimed a message, immediately allow another claim check on the next Receive()
	// so multi-message crash backlogs drain promptly.
	if len(msgs) > 0 {
		b.lastAutoClaimAt = time.Time{}
	}
	b.mu.Unlock()

	if len(msgs) > 0 {
		return msgs[0], true, nil
	}
	return redis.XMessage{}, false, nil
}

func (b *RedisBroker) buildDelivery(msg redis.XMessage) application.Delivery {
	b.inflight.Add(1)
	var finishOnce sync.Once
	finish := func() {
		finishOnce.Do(func() {
			b.inflight.Done()
		})
	}

	rawVal, hasPayload := msg.Values["payload"]
	var rawStr string
	var parseErr error
	var ev domain.SupplyEvent

	if !hasPayload {
		rawBytes, _ := json.Marshal(msg.Values)
		rawStr = string(rawBytes)
		parseErr = fmt.Errorf("missing 'payload' field in redis stream entry %s", msg.ID)
	} else {
		switch v := rawVal.(type) {
		case string:
			rawStr = v
		case []byte:
			rawStr = string(v)
		default:
			rawStr = fmt.Sprintf("%v", v)
		}
		if err := json.Unmarshal([]byte(rawStr), &ev); err != nil {
			parseErr = fmt.Errorf("malformed supply event JSON in redis stream entry %s: %w", msg.ID, err)
		}
	}

	ackFn := func(ctx context.Context) error {
		defer finish()
		if ctx == nil {
			ctx = context.Background()
		}
		ackCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		if err := b.client.XAck(ackCtx, b.stream, b.group, msg.ID).Err(); err != nil {
			return err
		}
		if d := b.depth.Add(-1); d < 0 {
			b.depth.Store(0)
		}
		return nil
	}

	nackFn := func(_ context.Context, _ string) error {
		// Deliberately leave the entry unacknowledged in Redis PEL so it remains
		// visible to XPENDING and recoverable via XAutoClaim after MinIdle.
		defer finish()
		return nil
	}

	return application.Delivery{
		MessageID:  msg.ID,
		Event:      ev,
		RawPayload: rawStr,
		ParseErr:   parseErr,
		Ack:        ackFn,
		Nack:       nackFn,
	}
}

// PendingCount returns the current number of unacknowledged entries in the Redis consumer group PEL.
func (b *RedisBroker) PendingCount(ctx context.Context) (int64, error) {
	if err := b.ensureGroup(ctx); err != nil {
		return 0, err
	}
	p, err := b.client.XPending(ctx, b.stream, b.group).Result()
	if err != nil {
		return 0, err
	}
	return p.Count, nil
}

func (b *RedisBroker) Depth() int {
	return int(b.depth.Load())
}

func (b *RedisBroker) Capacity() int {
	return b.capacity
}

// StopReceiving halts Receive() from pulling new entries while keeping the Redis client open
// so already-delivered in-flight messages can finish processing and invoke Ack() cleanly.
func (b *RedisBroker) StopReceiving() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.stopped {
		b.stopped = true
		close(b.stopCh)
	}
}

// Close stops receiving new entries, briefly waits for in-flight deliveries to Ack/Nack,
// and closes the underlying Redis connection.
func (b *RedisBroker) Close() error {
	b.StopReceiving()

	done := make(chan struct{})
	go func() {
		b.inflight.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.closed {
		b.closed = true
		return b.client.Close()
	}
	return nil
}

func (b *RedisBroker) Ping(ctx context.Context) error {
	pingCtx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	if err := b.client.Ping(pingCtx).Err(); err != nil {
		return fmt.Errorf("%w: redis ping failed: %v", domain.ErrUpstreamUnavailable, err)
	}
	return nil
}
