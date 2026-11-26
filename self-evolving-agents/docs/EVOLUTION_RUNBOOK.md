> **Historical document (v0.1).** The `/api/*` endpoints it mentions were replaced by `/api/v1` in v0.2 (see [API.md](API.md) and [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)). Behaviour descriptions may be outdated.

# Local AI evolution runbook

This demo runs Ollama locally. It does **not** train model weights or rewrite Java code. It proposes and learns from bounded traffic policies. A human must approve promotion.

## Start

```bash
cp .env.example .env
# Set a strong DEMO_ADMIN_TOKEN and GRAFANA_PASSWORD

docker compose up --build -d
docker compose exec ollama ollama pull qwen2.5:3b
set -a; . ./.env; set +a
curl -fsS http://localhost:8080/actuator/health
```

First Ollama model pull requires internet. Model data persists in a named Docker volume. On CPU-only hardware inference may take minutes; allow for the HTTP timeout.

## Enable AI (advisory)

```bash
curl -s -X PUT -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"enabled":true}' http://localhost:8080/api/admin/ai/enabled
```

This enables model proposals, **not** scheduled runs. It persists in Redis. To turn it off, send `{"enabled":false}` to the same endpoint.

## Generate demand

```bash
docker compose --profile load run --rm k6
```

The `/api/shop` endpoint records each *new* arrival, not queue ticket polls. A 429 can be an intentional limit, while 5xx errors are unexpected. The simulator replays 60 completed seconds of observed arrival counts. After a long pause the window expires and you must run load again.

## Trigger a single evolution cycle

```bash
curl -s -X POST -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" http://localhost:8080/api/admin/evolution/cycle
```

The cycle: read observed arrival counts, call Ollama, parse a constrained JSON candidate, replay both policies against the same arrival series, apply safety checks, store the experiment and leave a passing candidate for approval. Rejected candidates are removed. Ollama errors do not modify the active policy.

```bash
curl -s -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" http://localhost:8080/api/admin/evolution/history
curl -s http://localhost:8080/api/policy
```

If the result is `candidate_passed`, review it and approve manually:

```bash
curl -s -X POST -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" http://localhost:8080/api/admin/approve
```

Approval currently also checks the original fixed-capacity proxy, not the stored replay result. **Do not treat this as a production approval gate.** Use the returned history and validate with a separate k6 run after approval. The runtime queue can contain tickets from previous experiments; reset demo state when performing controlled comparisons.

## Enable automatic *proposals*

```bash
curl -s -X PUT -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"enabled":true}' http://localhost:8080/api/admin/evolution/automatic
curl -s -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" http://localhost:8080/api/admin/evolution/status
```

The scheduler checks approximately once a minute (configurable with `DEMO_EVOLUTION_INTERVAL_MS`). It runs only when both flags are enabled, at least 120 new arrivals were observed in the last 60 seconds, and there is no pending candidate. It never approves candidates. Disable with `{"enabled":false}` at the same endpoint.

## Roll back

```bash
curl -s -X POST -H "X-Demo-Admin: $DEMO_ADMIN_TOKEN" http://localhost:8080/api/admin/rollback
```

Rollback changes the policy, not existing Redis queue tickets. For a full **destructive** demo reset, `docker compose down -v` removes all named volumes including Ollama model files; a model re-download is then required. Prefer preserving Ollama data and cleaning only demo Redis keys for repeatable runs.

## Limitations and safety

- Replay is a deterministic one-second queue simulation with a 30-second drain window, not real HTTP latency, 5xx, fairness, or CPU measurements.
- Observed arrivals are real counts but are not a controlled open-loop workload. k6 VUs and queue polling affect load generation.
- The AI reads prior experiment records (last five); that is retrieval-augmented operational memory, not model fine-tuning.
- A scheduled cycle may run during a benchmark, so disable automatic mode when measuring baselines.
- Redis keys and policies are demo-grade; admin API is a shared token, no TLS or RBAC. Bind services to localhost.
- Policy promotions and history are not atomic across all Redis operations. This is not production-ready.
- Ollama may propose invalid or ineffective policies; validation can reject all proposals, which is expected.
