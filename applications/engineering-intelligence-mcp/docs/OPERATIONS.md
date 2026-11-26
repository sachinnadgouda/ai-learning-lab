# Operations & Runbook

## 1. Startup & Runtime Checklist

1. Confirm Node.js version is `>= 22.13.0` (`node -v`).
2. Start in `GITHUB_MODE=demo` and `PERMISSION_MODE=read-only` to validate client wiring before supplying live credentials.
3. For live usage:
   - Set `GITHUB_MODE=live` and provide a least-privilege `GITHUB_TOKEN`.
   - Set `ALLOWED_REPOS` to the exact comma-separated `owner/repo` list.
   - Set persistent `AUDIT_DB_PATH` on a durable volume (e.g., `/app/data/audit.db`).
   - If enabling writes (`PERMISSION_MODE=write-with-confirmation`), configure a stable 32+ character `CONFIRMATION_SECRET`.

## 2. Health & Metrics Endpoints & Trust Boundary

Set `HEALTH_PORT=8081` (bound to `127.0.0.1` by default) to expose:
- `GET /health` and `GET /ready`: returns `200` when GitHub circuit is not `OPEN` and SQLite audit store responds; returns `503` when degraded.
- `GET /metrics`: Prometheus text exposition format (enabled by default when bound to loopback).

Trust boundary controls:
- **Local deployments (`HEALTH_HOST=127.0.0.1`):** `/health`, `/ready`, and `/metrics` are reachable only on loopback; Docker Compose binds `${HEALTH_PORT:-8081}:8081` explicitly on `127.0.0.1` and defaults `METRICS_ENABLED=false` when the container listens on `0.0.0.0` inside Docker.
- **Shared deployments (`DEPLOYMENT_MODE=shared-server`):** Binding `HEALTH_HOST` to a non-loopback address with `METRICS_ENABLED=true` fails fast at startup unless `METRICS_AUTH_TOKEN` is configured (clients must supply `Authorization: Bearer <token>` or `X-Metrics-Token: <token>`, checked in constant time; missing/invalid credentials return `401`). Alternatively set `METRICS_ENABLED=false` (returns `404`) and scrape via a private loopback sidecar.

Key metrics to alert on:
- `mcp_prompt_injection_warnings_total` sudden spikes -> investigate adversarial PRs/issues in target repositories.
- `mcp_tool_executions_total{outcome="DENIED"}` spikes -> potential unauthorized write attempts or misconfigured `ALLOWED_REPOS`.
- `github_rate_limit_remaining < 200` -> approaching GitHub REST API hourly quota.

## 3. Incident Troubleshooting

| Symptom | Likely Cause | Action |
|---|---|---|
| `AUTH_FAILED` | Expired/revoked `GITHUB_TOKEN` | Rotate PAT and restart server |
| `RATE_LIMITED` | GitHub primary or secondary rate limit hit | Check `github_rate_limit_remaining`, lower `GITHUB_MAX_PAGES`, or wait for reset |
| `CIRCUIT_OPEN` | Consecutive GitHub 5xx or network timeouts | Check GitHub status; circuit automatically probes `HALF_OPEN` after cooldown |
| `AI_PROVIDER` returning `usedFallback: true` | Ollama/LLM unreachable, timed out, or returned malformed JSON | Inspect `stderr` JSON logs for `ai.summarizer.fallback`; deterministic analysis continues unaffected |
| MCP client parse errors | Unexpected output on `stdout` | Ensure no external process writes to `stdout`; all server logs go exclusively to `stderr` |
