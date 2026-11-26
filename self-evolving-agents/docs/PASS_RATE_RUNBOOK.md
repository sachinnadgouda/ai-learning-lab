# Pass-rate runbook: baseline vs AI-evolved policy

All numbers below were produced on 2026-10-29 on the local Docker stack with the real **`qwen2.5:3b` model in the `ollama` container** (no stand-in). Raw outputs are written to `load/results/*.json` (git-ignored; regenerate with `scripts/run-passrate.sh`).

## What "pass" means
A *customer* arrives at `GET /api/v1/shop`, honours `Retry-After` on 429, polls waiting-room tickets every 0.5 s, and gives up after **20 s**. **Pass = the customer eventually receives HTTP 200.** The backend is the simulated 30 ms endpoint; the modeled backend capacity is 50 rps (`DEMO_BACKEND_CAPACITY`).

Load: `load/pass-rate.js`, constant arrival of **55 new customers/s** (above capacity 50, so ≥ 9 % must be turned away at best) for 180 s. Retries make raw HTTP arrivals far higher (see "incoming" below).

## Setup (reproduce)
```bash
docker compose up --build -d                     # model qwen2.5:3b already pulled
T=$(grep ^DEMO_ADMIN_TOKEN .env | cut -d= -f2-); H="X-Demo-Admin: $T"
# deliberately under-tuned starting policy (the shipped default is 60/s, queue 500)
docker compose exec -T redis redis-cli set demo:policy:active '{"version":1,"mode":"STATIC","admissionPerSecond":25,"maxQueue":20,"simulatedWorkMs":30}'
curl -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":false}' localhost:8080/api/v1/flags/automatic
sh scripts/run-passrate.sh baseline 55 180s                       # 1. baseline, AI idle
curl -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":true}' localhost:8080/api/v1/flags/ai
sh scripts/run-passrate.sh ai-cycle-load 55 150s &                 # 2. traffic while the AI cycle runs
sleep 40; curl -X POST -H "$H" localhost:8080/api/v1/evolution/cycles
curl -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"candidateFingerprint":"2:EVOLVED:45:600:30","reason":"reviewed"}' localhost:8080/api/v1/policies/candidate/approve
wait; sh scripts/run-passrate.sh after-ai 55 180s                  # 3. after approval
curl -X POST -H "$H" -H 'Content-Type: application/json' -d '{"reason":"check"}' localhost:8080/api/v1/policies/rollback
```
(The approval step was a human decision after reading the evaluation; the fingerprint comes from the cycle response.)

## Results

| | Baseline (policy v1: STATIC 25/s, queue 20) | AI-evolved (policy v2: EVOLVED 45/s, queue 600) |
|---|---|---|
| **Customers passed (HTTP 200)** | 4 850 of 9 554 → **50.8 %** | 8 392 of 9 293 → **90.3 %** |
| Customers gave up (20 s) | 4 704 | 901 |
| Served directly / via waiting room | 4 850 / 0 | 45 / 8 347 |
| Time to be served p50 / p95 | 34 ms / 14.1 s | 12.1 s / 15.1 s |
| HTTP 429 responses | 101 471 | 15 406 |
| Total HTTP requests (incl. retries/polls) | 106 321 | 201 869 |
| Observed backend throughput (last 60 s, Prometheus-style window) | 25.0 rps | 43.2 rps |
| Observed incoming RPS (60 s window, includes retries) | 636 | 188 |
| Backend latency p50 / p95 / p99 | 31 / 34 / 35 ms | 30 / 31 / 32 ms |
| Backend 5xx rate | 0 % | 0 % |
| Queue depth / p95 queue wait (end of run) | 0 / 0 s | 600 (full) / 14.5 s |

The AI cycle itself (`load/results/cycle1.json`, `invocation-final.json`):
- Model `qwen2.5:3b`, duration **19.1 s**, 797 prompt tokens, 76 completion tokens, temperature 0.2.
- Decision **PROPOSE** 25/s, queue 20 → **45/s, queue 600**; stored as policy candidate v2.
- Independent evaluation (simulated replay of 19 728 observed arrivals): baseline admitted 1 303, candidate 3 300; est. p95 wait 14 s; all safety gates passed (`valid=true`). These estimates are **modeled, not measured**; the table above is measured.
- Lifecycle recorded: OBSERVED → TRIGGERED → AI_CALLED ×2 → PROPOSED → EVALUATED → APPROVED → ACTIVATED → ROLLED_BACK (rollback joined the same cycle).
- Prometheus: `demo_ai_invocations_total{outcome="success"}`, `demo_policy_approvals_total`, `demo_policy_rollbacks_total` all incremented; after rollback `demo_policy_active_version` returned to 1.

## How the AI improved it
Under the baseline the active policy admitted only 25 customers/s and had almost no waiting room, so every other arrival was rejected and had to hammer the endpoint (≈ 636 raw rps for 55 real customers/s). The AI saw `admittedRps 22.5 / rejectedRps 306` and proposed raising admission to 45/s — below the modeled capacity of 50, so the safety evaluation passed — and enlarging the queue so surplus customers wait (≈ 12 s) instead of being bounced. Result: pass rate 50.8 % → 90.3 %, retry traffic roughly halved, with backend latency and error rate unchanged.

## Caveats (honest reading)
- The starting policy was set deliberately poor to give the AI something to fix. The shipped default (60/s, queue 500) is not under-tuned for this load.
- Ceiling: 55 customers/s offered vs 45/s admitted caps passes near 82 % in steady state; the 90.3 % includes the initial queue drain buffer (queue fills to 600 and customers waiting past 20 s abandon). Longer runs trend toward the cap.
- The cycle during load (`ai-cycle-load`, 51.8 %) mixes pre- and post-approval time and is not a comparison point.
- The model echoed "311.97" from a previous experiment's rationale instead of the current `328.8` rps in the metrics. The decision was still sound, but it shows small models can mis-cite numbers; that is why evaluation and human approval exist. The AI's text is a recommendation only.
- The decision is repeatable in direction but not byte-for-byte (LLM); the two real runs both proposed 45/600.
- One approved candidate, one model, one load shape, single run each: not statistically significant.

## Defects found and fixed along the way
The first two after-AI attempts scored **42 %** and later a collapse (**34 %**, 0 admitted/s with a full queue) because of waiting-room defects (900 s tickets for abandoned customers, head-only admission, dead tickets in the window). These were fixed (see [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)) and the numbers above are from the fixed build. An earlier 97.9 % run on the intermediate build was on a fresh queue and was not reproducible, so it is not reported as a result.
