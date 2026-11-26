package broker

import (
	"context"
	"errors"
	"io"
	"testing"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

func TestMemoryBroker_BackpressureAndDrain(t *testing.T) {
	b := NewMemoryBroker(2)
	ctx := context.Background()

	if err := b.Publish(ctx, domain.SupplyEvent{EventID: "1"}); err != nil {
		t.Fatalf("publish 1: %v", err)
	}
	if err := b.Publish(ctx, domain.SupplyEvent{EventID: "2"}); err != nil {
		t.Fatalf("publish 2: %v", err)
	}
	if err := b.Publish(ctx, domain.SupplyEvent{EventID: "3"}); !errors.Is(err, domain.ErrQueueFull) {
		t.Fatalf("expected ErrQueueFull, got %v", err)
	}
	if b.Depth() != 2 || b.Capacity() != 2 {
		t.Fatalf("unexpected depth/cap: %d/%d", b.Depth(), b.Capacity())
	}

	// Close broker -> buffered messages should still drain cleanly before io.EOF.
	if err := b.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	d1, err := b.Receive(ctx)
	if err != nil || d1.Event.EventID != "1" {
		t.Fatalf("expected drained event 1, got %+v err=%v", d1, err)
	}
	d2, err := b.Receive(ctx)
	if err != nil || d2.Event.EventID != "2" {
		t.Fatalf("expected drained event 2, got %+v err=%v", d2, err)
	}
	_, err = b.Receive(ctx)
	if !errors.Is(err, io.EOF) {
		t.Fatalf("expected io.EOF after drain, got %v", err)
	}
}

func TestRedisBroker_DegradedPingWhenUnreachable(t *testing.T) {
	// Port 1 is unreachable on localhost; Ping must return ErrUpstreamUnavailable quickly.
	rb := NewRedisBroker("127.0.0.1:1", "test-stream", "test-group", 10)
	defer func() { _ = rb.Close() }()

	if err := rb.Ping(context.Background()); !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("expected ErrUpstreamUnavailable from unreachable Redis, got %v", err)
	}
}
