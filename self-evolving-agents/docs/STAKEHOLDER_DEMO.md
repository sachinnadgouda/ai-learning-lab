# Stakeholder Demo: the AI tunes the traffic policy and the success rate rises

**Story in one sentence:** a flash-sale endpoint is protected by a deliberately under-tuned traffic policy; ~10 000 requests arrive and only **50.8 %** of customers are served; the AI reads the measured metrics, recommends a new policy; the system *independently* evaluates it; a human approves it; the next ~9 300 requests see **90.3 %** served – with backend latency and errors unchanged.

All figures are from a real run on 2026-10-29 (local Docker, real `qwen2.5:3b` model in the `ollama` container). Raw data: `load/results/*.json`. Full method: [PASS_RATE_RUNBOOK.md](PASS_RATE_RUNBOOK.md).

> **"Served"** = the customer eventually received HTTP 200 (they retry on 429, wait in the waiting room if offered a ticket, and give up after 20 s).

---
## 0. Before the audience arrives (5 min)

```bash
cd "<repo>"
docker compose up --build -d                    # first time only; model qwen2.5:3b is already in the ollama volume
docker compose ps                               # all services Up / healthy
T=$(grep ^DEMO_ADMIN_TOKEN .env | cut -d= -f2-)  # your admin token
H="X-Demo-Admin: $T"
```
Reset to the demo starting point (an intentionally cautious policy: 25 admitted/s, queue of 20):
```bash
docker compose exec -T redis redis-cli del demo:queue demo:policy:candidate demo:policy:evaluation demo:policy:history
docker compose exec -T redis redis-cli set demo:policy:active \
  '{"version":1,"mode":"STATIC","admissionPerSecond":25,"maxQueue":20,"simulatedWorkMs":30}'
curl -s -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":false}' localhost:8080/api/v1/flags/ai
curl -s -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":false}' localhost:8080/api/v1/flags/automatic
```
Open these tabs:

| Tab | URL | Login |
|---|---|---|
| Dashboard / AI Inspector | http://localhost:4200 | `DEMO_ADMIN_TOKEN` |
| Grafana | http://localhost:3000 | `admin` / `GRAFANA_PASSWORD` from `.env` → dashboard *Self-Evolving Traffic Demo* |
| Prometheus (optional) | http://localhost:9090 | – |

---
## 1. Set the scene (2 min, talk only)
*"We have a protected endpoint, a simulated 30 ms purchase call that can safely handle about **50 requests/s**. A traffic policy decides how many requests per second we let through and how many customers may wait in a queue. Today the policy is hard-coded and conservative. We'll let an AI propose a better one – but the AI is never allowed to change production on its own. Its proposal is re-checked by our own code and must be approved by a person."*

Show **Policies** page: Active policy **v1 – STATIC, 25 admissions/s, queue 20**. AI disabled in **Settings**.

---
## 2. Round 1 – baseline, AI off (4 min)
Start the load (55 new customers/s, 180 s ≈ **9 500 customers**, ≈ 106 000 HTTP calls because rejected customers keep retrying):
```bash
sh scripts/run-passrate.sh baseline 55 180s
```
While it runs, show **Overview** and **Grafana** (refreshes every few seconds). Say: *"Incoming requests are far above what we admit. Watch the rejected line."*

### What you will see (real numbers)
| Metric | Where to look | Baseline value |
|---|---|---|
| Customers served | k6 summary / `load/results/baseline.json` | **4 850 of 9 554 → 50.8 %** |
| Customers who gave up | same | 4 704 |
| HTTP 429 (rejected) responses | same | 101 471 |
| Incoming RPS (incl. retries) | Overview, Grafana *Requests/s* | ≈ 636 |
| Admitted / backend RPS | Overview | **25** (policy cap) |
| Backend p95 latency | Overview | 34 ms |
| Backend 5xx rate | Overview | 0 % |
| Queue depth | Overview | 0 (queue unused in STATIC mode) |

*Talk track:* "Half the customers are turned away – yet the backend is **almost idle**: 25 rps of its 50 rps capacity, 34 ms latency, zero errors. We are throwing away revenue while protecting a backend that did not need that much protection."

Same facts via API:
```bash
curl -s -H "$H" 'localhost:8080/api/v1/traffic/metrics?windowSeconds=60' | python3 -m json.tool
```
(`"source":"OBSERVED"` – these are measured values, not simulated.)

---
## 3. Turn the AI on and run a cycle (5 min)
Start traffic again in a second terminal so the AI sees live load (keep it running about 150 s):
```bash
sh scripts/run-passrate.sh ai-cycle-load 55 150s &
sleep 40
```
In the UI: **Settings → enable AI** (or `curl -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":true}' localhost:8080/api/v1/flags/ai`). Leave *automatic evolution* **off** so you control timing. Mention: *"Switching AI off later never rolls back an approved policy."*

Trigger one evolution cycle (UI **Policies → Run AI evolution cycle**, or):
```bash
curl -s -X POST -H "$H" localhost:8080/api/v1/evolution/cycles | python3 -m json.tool
```
It returns in ~20 s (real inference on the local model).

### 3a. What the AI was shown – **AI Inspector** page (left panel)
Select the newest invocation. Real prompt content (exact text stored in PostgreSQL, not reconstructed):

- Current policy: `{"version":1,"mode":"STATIC","admissionPerSecond":25,"maxQueue":20,"simulatedWorkMs":30}`
- Measured metrics over the last 60 s (wrapped as *untrusted data* so the telemetry can't hijack the prompt):
  `incomingRps 328.8 · admittedRps 22.5 · rejectedRps 306.3 · backendP95LatencyMs 33 · backend5xxRate 0 · queueDepth 0 · arrivalsPeakPerSecond 643 · configuredBackendCapacityPerSecond 50`
- Previous experiments (lessons from earlier cycles).
- Model config: `qwen2.5:3b`, temperature 0.2, JSON-only output.

*Talk track:* "This is exactly what we sent. No hidden context. Notice it is told the backend capacity is 50/s."

### 3b. What the AI answered – right panel
Raw model response (real):
```json
{"decision":"PROPOSE","admissionPerSecond":45,"maxQueue":600,
 "rationale":"Current observed RPS (311.97) is significantly higher than the baseline (25), indicating potential overloading. Increasing admissionPerSecond to 45 and maxQueue to 600 helps to manage the observed traffic."}
```
Run stats shown in the Inspector: duration **19.1 s**, 797 prompt tokens, 76 completion tokens.

*Talk track:* "The AI recommends going from 25 to 45 admitted per second – deliberately *below* the 50/s capacity – and giving customers a 600-place waiting room instead of bouncing them."

**Be honest about one detail** (this builds trust): the model quoted 311.97 rps from an earlier experiment's text; the current metric was 328.8. The direction was right, but this is exactly why we don't trust the AI's words – see next step.

### 3c. Independent validation – right panel, "Independent validation" (distinct from "AI recommendation")
Our own code, not the AI, checks:
1. Response parses; values are inside hard bounds (admission 1–1000, queue 1–10 000). ✔
2. Not a no-op (a proposal identical to the current policy would create no version). ✔
3. **Replay evaluation** – the *same observed arrivals* are replayed through the old and the new policy in a model (**Policies → Evaluation** or **Experiments**; labelled **SIMULATED**):

| Simulated replay of 19 728 observed arrivals | Baseline v1 | Candidate v2 |
|---|---|---|
| Admitted | 1 303 | **3 300** |
| Rejected | 17 418 | 15 421 |
| Est. p95 backend latency | 30 ms | 30 ms |
| Est. error rate | 0 % | 0 % |
| Est. p95 queue wait | 0 s | 14 s |
| Safety gates (p95 ≤ 150 ms, errors ≤ 1 %, wait ≤ 30 s, no fewer completions, no more rejections) | – | **all passed (valid = true)** |

*Talk track:* "The AI said *what*; our evaluator checks *whether it is safe*. It would have rejected e.g. 90/s because that exceeds backend capacity – we proved that earlier with a scripted proposal. Note these are **modeled** numbers; real ones come next."

View the whole lifecycle on **Evolution Timeline**:
`OBSERVED → TRIGGERED → AI_CALLED → PROPOSED → EVALUATED` – status *awaiting approval*. Nothing has changed in production yet: **Policies** still shows Active v1.

---
## 4. Human approval (2 min)
**Policies** page: candidate v2 shown side by side with v1 (25→45, 20→600). The **Approve** button is only enabled because the candidate was evaluated and passed. Click **Approve**, read the confirmation dialog, confirm. (API equivalent – approval is bound to the exact evaluated candidate:)
```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' \
 -d '{"candidateFingerprint":"2:EVOLVED:45:600:30","reason":"within capacity 50, all gates passed"}' \
 localhost:8080/api/v1/policies/candidate/approve
```
Timeline now adds `APPROVED → ACTIVATED`. Active policy = **v2, EVOLVED, 45/s, queue 600**. (A wrong fingerprint returns `409` – demonstrated earlier; mention if asked about governance.)

Let the background ~150 s load finish, then clear the leftover queue so Round 2 starts clean:
```bash
wait; docker compose exec -T redis redis-cli del demo:queue
```

---
## 5. Round 2 – same load, AI-approved policy (4 min)
```bash
sh scripts/run-passrate.sh after-ai 55 180s
```
Watch **Overview/Grafana**: rejected line collapses, admitted rises to ≈ 45, queue depth fills (customers waiting instead of being bounced), latency flat.

### Results (real)
| | Round 1 – baseline (v1: 25/s, queue 20) | Round 2 – after AI (v2: 45/s, queue 600) | Change |
|---|---|---|---|
| **Customers served** | 4 850 / 9 554 = **50.8 %** | 8 392 / 9 293 = **90.3 %** | **+39.5 pts** |
| Customers who gave up | 4 704 | 901 | –81 % |
| HTTP 429 responses | 101 471 | 15 406 | –85 % |
| Total HTTP calls (retry storm) | 106 321 | 201 869* | see note |
| Backend throughput (measured) | 25 rps | 43 rps | +73 % |
| Incoming RPS in last 60 s | 636 | 188 | –70 % |
| Backend p95 / p99 latency | 34 / 35 ms | 31 / 32 ms | unchanged |
| Backend 5xx | 0 % | 0 % | unchanged |
| Time to be served p50 / p95 | 34 ms / 14.1 s | 12.1 s / 15.1 s | customers now *wait* instead of being refused |
| Queue p95 wait / depth (end) | 0 s / 0 | 14.5 s / 600 (full) | by design |

\* Total calls include 0.5 s ticket polling by queued customers; the *rejection/retry* traffic (429s) fell 85 %.

*Talk track:* "Same offered load, same backend. Success went from one in two to nine in ten. Backend latency and errors didn't move – the AI found headroom we were wasting, and stayed under the 50/s ceiling. The cost: customers wait about 12 seconds in a queue rather than being told 'try again'."

Honest caveat for Q&A: offered load is 55/s while we admit 45/s, so the steady-state ceiling is ~82 %; the 90.3 % benefits from the queue buffer at the start of the run. It is one run per condition.

---
## 6. Where to point when asked "how do I verify this myself?"

| Question | Where |
|---|---|
| What exactly did the AI see/answer? | UI **AI Inspector** (split view; copy buttons; compare two invocations) or `GET /api/v1/ai/invocations/{id}` (fields `prompt.system`, `prompt.user`, `rawResponse`, `timing`) |
| Why was it accepted? | **AI Inspector → Independent validation**, **Policies → Evaluation / Safety gates**, **Experiments** (SIMULATED vs OBSERVED labels) |
| What happened in what order? | **Timeline** (`/api/v1/evolution/cycles/{id}/timeline`) |
| Who approved? | **Logs & Diagnostics** (audit events, filter by cycle) |
| Did traffic actually improve? | **Overview** + Grafana panels: *Requests/s*, *Admitted vs rejected*, *Backend latency*, *Queue depth*, *Active policy*, *AI invocations*, *Evolution cycles* |
| Prometheus counters | `demo_ai_invocations_total`, `demo_ai_decisions_total{decision="PROPOSE"}`, `demo_policy_approvals_total`, `demo_policy_active_version`, `demo_policy_active_admission_limit` |

Useful one-liners:
```bash
curl -s -H "$H" 'localhost:8080/api/v1/ai/invocations?size=5' | python3 -m json.tool
curl -s -H "$H" localhost:8080/api/v1/policies | python3 -m json.tool
curl -s localhost:8080/actuator/prometheus | grep -E '^demo_(ai_|policy_|evolution_)'
```

---
## 7. Safety and rollback demo (2 min, optional but persuasive)
*"What if we regret it?"* **Policies → Roll back** (confirm) or:
```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"reason":"demo rollback"}' localhost:8080/api/v1/policies/rollback
```
Policy returns to v1; the Timeline adds `ROLLED_BACK` on the same cycle; `demo_policy_active_version` goes back to 1. Also show: disable AI in Settings → no new Ollama calls, active policy keeps running.

## 8. Closing points
- The AI **recommends**; deterministic code **validates**; a **human approves**; rollback is one click.
- Every prompt and response is stored and inspectable; redaction and retention are configurable.
- Measured (Overview/Grafana) and simulated (evaluation) numbers are always labelled.
- Everything runs locally in Docker, including the model.

## 9. Known limits to state up front
- The starting policy was deliberately poor to give the AI something to fix; the shipped default is 60/s, queue 500.
- Small local model: it may mis-quote numbers (seen once) and results vary between runs; the evaluator + approval are the safety net.
- The load is synthetic (k6) and the backend is a simulated 30 ms endpoint; the "50 rps capacity" is a configured value (`DEMO_BACKEND_CAPACITY`), not measured hardware capacity.
- One run per condition – indicative, not statistically significant.
- Real-model cycle time depends on the machine (~19 s here; much slower on CPU-starved hosts – raise `DEMO_AI_TIMEOUT`).

## 10. Reset after the demo
```bash
docker compose exec -T redis redis-cli del demo:policy:active demo:queue demo:policy:candidate demo:policy:evaluation demo:policy:history
```
(restores the shipped default 60/s, queue 500).
