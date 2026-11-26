# ADR-001: Idempotent Worker Pool, Out-of-Order Watermarks, and Persistent DLQ

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

Supply-chain telemetry arrives asynchronously from carrier webhooks, ERP inventory draws, port congestion feeds, and supplier advisories. Producers routinely deliver duplicate webhooks on network retries, emit events out of chronological order across regions, or burst traffic during regional weather disruptions.

## Decision

1. **Bounded Intake Queue with Explicit Backpressure:** `EventBroker.Publish` is non-blocking against a bounded capacity (`QUEUE_CAPACITY`). Saturation immediately returns `domain.ErrQueueFull` (mapped to HTTP 429 with `Retry-After: 1`) rather than growing memory unboundedly or leaking goroutines.
2. **Atomic Transactional Event Processing & Idempotency:** Workers execute entity lookup, watermark check, entity state mutation, `supply_events` append, and `processed_events` claim completion inside a single SQLite transaction (`Repository.ProcessEventAtomic`). A partial failure rolls back cleanly so duplicate redeliveries cannot apply the same mutation twice, and a crashed or failed transaction remains recoverable. Processing counters (`events_processed_total`, `events_duplicate_total`, `events_dlq_total`) increment only after durable commit.
3. **Post-Commit Broker Acknowledgement & Redis Streams Crash Recovery:** `EventBroker.Receive` returns a `Delivery` envelope containing explicit `Ack` and `Nack` callbacks rather than acknowledging on read. For Redis Streams (`RedisBroker`), `XAck` is called only after `ProcessEventAtomic` or `RecordEventDLQAtomic` durably commits. Failed or interrupted deliveries remain in the consumer group PEL and are recovered on restart (via own-PEL drain `"0"`) or across crashed consumers via `XAutoClaim`. Malformed stream entries are routed explicitly to `dead_letter_events` and acknowledged only after durable DLQ persistence rather than being silently discarded.
4. **Per-Entity Watermarks for Out-of-Order Events:** Each entity persists `last_occurred_at`. Events arriving with `ev.OccurredAt < entity.LastOccurredAt` are appended to `supply_events` with `out_of_order=true` for complete chronological incident timelines and auditability, while preserving newer entity state.
5. **Exponential Backoff + Jitter and Persistent DLQ:** Transient failures retry up to `MAX_RETRIES` with capped exponential backoff plus random jitter. Permanent errors (`ErrInvalidInput`, `ErrNotFound`, poison payloads) or exhausted retries atomically persist the raw event into `dead_letter_events` and mark the claim `DLQ` (`RecordEventDLQAtomic`), exposing audited `ReplayDLQ`. If DLQ persistence itself fails, the broker delivery is nacked so the message stays pending for recovery.
6. **Graceful Drain on Shutdown:** `Processor.Shutdown` calls `StopReceiving()` to halt new Redis/memory deliveries, waits for in-flight workers to commit and ack their current deliveries, and then closes the broker so unstarted pending entries remain intact in the Redis PEL for the next consumer.

## Consequences

- Event ingestion stays fast (`202 Accepted`) and memory-bounded under load spikes.
- Duplicate webhook storms cannot double-deduct inventory or double-add shipment delay hours.
- Stale telemetry cannot silently clear an active disruption.
- Operators have full visibility and replay control over failed events without blocking healthy traffic.
