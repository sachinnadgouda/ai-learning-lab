> **Historical document (v0.1).** The `/api/*` endpoints it mentions were replaced by `/api/v1` in v0.2 (see [API.md](API.md) and [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)). Behaviour descriptions may be outdated.

# Roadmap and local acceptance checks

## Validate on a Docker-equipped machine

1. `docker compose config` succeeds.
2. `docker compose up --build -d` completes without errors.
3. `curl -f http://localhost:8080/actuator/health` returns healthy status.
4. `curl http://localhost:8080/api/policy` shows STATIC v1.
5. `docker compose --profile load run --rm k6` produces a real k6 summary.
6. `POST /api/admin/propose` requires the admin header.
7. `GET /api/admin/evaluate` labels its output as a capacity proxy.
8. `POST /api/admin/approve` changes the active policy.
9. At sufficiently high traffic, `/api/shop` returns 202 with a ticket, and polling eventually admits the FIFO head.
10. `POST /api/admin/rollback` restores the prior policy.
11. `docker compose down -v` clears state for a clean comparison.

## Phase 2: actual governed self-evolution

- Replace the fixed capacity proxy with offline replay using recorded workload traces.
- Run multiple trials with equal workload and confidence intervals.
- Compare static limiter, conventional adaptive controller, and evolved policies.
- Define promotion thresholds: p95 latency, 5xx, throughput, queue wait p95, fairness, and rejection rate.
- Add a typed local Ollama policy-proposal adapter; disallow generated executable code.
- Add immutable experiment logs, policy hashes, signed approvals, and explicit rollback triggers.
- Implement proper queue lifecycle, user-bound tokens, expiry cleanup, distributed deployments, and rate-limit keying.
- Add concurrency, Redis restart, queue expiry, security, and end-to-end integration tests.

## Ollama AI advisor increment

Implemented: Docker-hosted Ollama, disabled-by-default feature flag, admin status/toggle/propose endpoints, structured policy suggestions, numeric safety bounds, manual evaluation/approval/rollback, and documentation. Not implemented: automatic evolution scheduling, replay-based empirical scoring, persisted experiment memory, and autonomous policy promotion.
