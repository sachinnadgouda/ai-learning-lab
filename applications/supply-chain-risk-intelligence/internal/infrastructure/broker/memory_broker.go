package broker

import (
	"context"
	"io"
	"sync"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

// MemoryBroker is an in-process bounded buffered channel EventBroker with explicit backpressure.
type MemoryBroker struct {
	ch       chan domain.SupplyEvent
	capacity int
	mu       sync.RWMutex
	closed   bool
}

func NewMemoryBroker(capacity int) *MemoryBroker {
	if capacity <= 0 {
		capacity = 256
	}
	return &MemoryBroker{
		ch:       make(chan domain.SupplyEvent, capacity),
		capacity: capacity,
	}
}

func (b *MemoryBroker) Name() string { return "memory" }

func (b *MemoryBroker) Publish(_ context.Context, ev domain.SupplyEvent) error {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if b.closed {
		return domain.ErrUpstreamUnavailable
	}
	select {
	case b.ch <- ev:
		return nil
	default:
		return domain.ErrQueueFull
	}
}

func (b *MemoryBroker) wrapDelivery(ev domain.SupplyEvent) application.Delivery {
	return application.Delivery{
		MessageID: "mem-" + uuid.NewString()[:8],
		Event:     ev,
		Ack:       func(context.Context) error { return nil },
		Nack:      func(context.Context, string) error { return nil },
	}
}

// Receive drains buffered events even after Close() is called, returning io.EOF once closed and empty.
func (b *MemoryBroker) Receive(ctx context.Context) (application.Delivery, error) {
	// First prefer draining any already-buffered item without waiting on ctx.
	select {
	case ev, ok := <-b.ch:
		if !ok {
			return application.Delivery{}, io.EOF
		}
		return b.wrapDelivery(ev), nil
	default:
	}

	select {
	case ev, ok := <-b.ch:
		if !ok {
			return application.Delivery{}, io.EOF
		}
		return b.wrapDelivery(ev), nil
	case <-ctx.Done():
		// Drain remaining buffered item if available before exiting.
		select {
		case ev, ok := <-b.ch:
			if ok {
				return b.wrapDelivery(ev), nil
			}
		default:
		}
		return application.Delivery{}, ctx.Err()
	}
}

func (b *MemoryBroker) Depth() int {
	return len(b.ch)
}

func (b *MemoryBroker) Capacity() int {
	return b.capacity
}

// StopReceiving closes intake so buffered items drain cleanly before Receive returns io.EOF.
func (b *MemoryBroker) StopReceiving() {
	_ = b.Close()
}

func (b *MemoryBroker) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.closed {
		b.closed = true
		close(b.ch)
	}
	return nil
}

func (b *MemoryBroker) Ping(_ context.Context) error {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if b.closed {
		return domain.ErrUpstreamUnavailable
	}
	return nil
}
