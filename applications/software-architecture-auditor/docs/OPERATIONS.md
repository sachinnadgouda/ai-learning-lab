# Operations Runbook

## Startup Validation

When running `arch-auditor serve`, `AuditorConfig.validate(requireServerApiKey = true)` fails fast on:
- Missing or short (`< 16` chars) `AUDITOR_API_KEY`
- Invalid port, negative scanner limits, or invalid AI timeout/retry configuration
- Non-HTTP(S) `AI_BASE_URL` or blank `AI_MODEL` when `AI_PROVIDER != none`

## Persistence & Migrations

- SQLite runs in **WAL mode** (`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;`).
- Flyway automatically executes versioned SQL migrations from `classpath:db/migration` (`V1__init.sql`) on startup.
- Backup procedure: run SQLite online backup (`sqlite3 data/auditor.db ".backup backup.db"`) or snapshot the `auditor-data` Docker volume.

## Observability

- **Health:** `GET /health` (liveness) and `GET /ready` (verifies SQLite query execution).
- **Metrics:** `GET /metrics` exposes Prometheus counters:
  - `auditor_scans_total`
  - `auditor_verified_violations_total`
  - `auditor_cycles_total`
  - `auditor_file_cache_hits_total` / `auditor_file_cache_misses_total`
  - `auditor_ai_invocations_total{status=...}`
  - `auditor_ai_latency_ms_total`
  - `auditor_ai_prompt_injections_neutralized_total`
  - `auditor_http_requests_total{method,path,status}`
  - `auditor_http_request_duration_ms_total`
- **Logging:** Structured JSON logs on `stderr`; control verbosity with `AUDITOR_LOG_LEVEL`.
- **Graceful Shutdown:** JVM shutdown hook drains Ktor Netty connections (1.5s grace, 5s timeout) and closes the Ktor CIO AI HTTP client.

## Troubleshooting

| Symptom | Cause | Resolution |
|---|---|---|
| `403 Forbidden` on `POST /api/v1/scans` | Path outside `AUDITOR_ALLOWED_ROOTS` or symlink escape | Mount or configure the target directory in `AUDITOR_ALLOWED_ROOTS` |
| `422 Unprocessable Entity` | Invalid regex/glob or threshold in YAML/JSON rules | Inspect `detail` field in Problem Details JSON and fix rule syntax |
| `aiSuggestions.status == DEGRADED_UNAVAILABLE` | Ollama/OpenAI endpoint unreachable or timed out | Verify `AI_BASE_URL`, network connectivity, or `AI_REQUEST_TIMEOUT_MS`; deterministic findings remain valid |
| High `cacheMisses` across repeated scans | Ephemeral DB path or modified file timestamps/content | Mount persistent volume at `/data` and keep `AUDITOR_DB_PATH` stable |
