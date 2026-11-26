# Operations & Runbook

## Health, Readiness & Metrics

- **Liveness (`GET /health`):** Returns HTTP 200 whenever the Go HTTP server process is responsive.
- **Readiness (`GET /ready`):** Pings SQLite (`Store.Ping`) and the active `EventBroker` (`MemoryBroker.Ping` or `RedisBroker.Ping`). Returns HTTP 503 with `status: "degraded"` if either dependency fails.
- **Prometheus Metrics (`GET /metrics`):**
  - `events_ingested_total`, `events_processed_total`, `events_duplicate_total`, `events_out_of_order_total`, `events_dlq_total`
  - `event_processing_duration_seconds` (histogram)
  - `queue_depth`, `active_incidents`
  - `ai_invocations_total{provider,outcome}`
  - `http_requests_total`

## Alerting Thresholds (Recommended)

1. `queue_depth / QUEUE_CAPACITY > 0.8` for 2 minutes -> Scale `WORKER_COUNT` or investigate downstream DB latency.
2. `increase(events_dlq_total[5m]) > 0` -> Inspect `GET /api/v1/dlq`, resolve missing master data or poison schema, and replay via `POST /api/v1/dlq/{id}/replay`.
3. `ai_invocations_total{outcome="degraded"}` rising -> Check Ollama/OpenAI connectivity or circuit breaker state; core risk scoring and shortage alerting remain unaffected.

## DLQ Replay Runbook

```bash
# 1. Inspect dead-lettered events
curl -s -H "X-API-Key: $API_KEY_OPERATOR" http://127.0.0.1:8080/api/v1/dlq

# 2. Remediate root cause (e.g., create missing supplier/SKU entity)
# 3. Replay specific DLQ entry
curl -s -X POST -H "X-API-Key: $API_KEY_OPERATOR" \
  http://127.0.0.1:8080/api/v1/dlq/<dlq-id>/replay
```

## Backup & Persistence

- SQLite runs in WAL mode (`supply_chain.db`, `supply_chain.db-wal`, `supply_chain.db-shm`) mounted on `/data` in Docker.
- In Compose mode, Redis runs with AOF (`--appendonly yes`) persisted to `redis_data`.
