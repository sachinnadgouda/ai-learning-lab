# Operations & Runbook — Insurance Claims Intelligence

## 1. Local & Container Lifecycle

- **Database Migrations:** Managed via Alembic (`alembic upgrade head` or `uv run python -m claims_intel.cli migrate`). When `AUTO_MIGRATE=true`, migrations run automatically during FastAPI lifespan startup.
- **SQLite Durability:** Local SQLite runs in `WAL` mode (`PRAGMA journal_mode=WAL`, `PRAGMA foreign_keys=ON`) for concurrent readers during writes.
- **PostgreSQL 17 Deployment:** Configured via `DATABASE_URL=postgresql+psycopg://...` in `compose.yaml` with `pool_pre_ping=True`.

## 2. Health, Readiness & Metrics

- **Liveness:** `GET /health` -> HTTP 200 when FastAPI is responsive.
- **Readiness:** `GET /ready` -> probes `SELECT 1` on the configured database and reports active `ai_provider` and `embedding_provider`.
- **Prometheus Metrics:** `GET /metrics` exposes:
  - `claims_http_requests_total{method,path,status}`
  - `claims_http_request_duration_seconds_sum` / `_count`
  - `claims_ai_calls_total{provider,operation,status}`
  - `claims_ai_latency_ms_sum{provider,operation}`
  - `claims_ai_tokens_total{provider,operation,kind}`
  - `claims_domain_events_total{event}`

## 3. Incident Troubleshooting

| Symptom | Investigation Steps |
|---|---|
| `HTTP 502 UPSTREAM_AI_UNAVAILABLE` | Inspect JSON logs by `correlation_id`. Verify `OLLAMA_BASE_URL` or `OPENAI_BASE_URL` reachability, adjust `AI_READ_TIMEOUT_SECONDS`, or switch temporarily to `AI_PROVIDER=local` to maintain deterministic extraction & Q&A availability. |
| Image uploads show `ocr_status=UNAVAILABLE` | Expected when the `tesseract` binary is absent; embedded PNG/EXIF metadata is still indexed. Install `tesseract-ocr` on the host/image and verify `TESSERACT_CMD` to enable raster OCR. |
| `HTTP 403 AUTONOMOUS_DECISION_PROHIBITED` | An AI service key attempted to call `POST /api/v1/claims/{id}/review`. Inspect the claim's `/audit` trail for `REVIEW_DECISION_BLOCKED` and escalate to a human reviewer. |
