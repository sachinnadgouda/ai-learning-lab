# Refactoring Plan

Baseline (before changes): Gradle, Java 21, Spring Boot 3.4.5, one flat package `dev.demo.traffic` (9 classes, ~600 LOC),
3 unit-test classes (all passing: `./gradlew test`). Docker daemon was not running during analysis.

## 1. Existing behaviour (source of truth: the code)

| Class | Responsibility today | Coupling / violation |
|---|---|---|
| `Policy` | record, bounds check (admission 1..5000, queue 0..100000, work 0..1000) | mode is a `String` |
| `PolicyService` | Redis keys `demo:policy:{active,candidate,history}`; deterministic 0.8x proposal; envelope validation; synthetic-trace evaluation; approve; rollback | persistence + domain rules + evaluation in one class; approval re-evaluates a *synthetic* trace, so approval is not bound to the evaluation that was shown |
| `AiEvolutionService` | Redis flag `demo:ai:enabled`; builds one concatenated prompt; calls Ollama `/api/generate`; parses; validates; saves candidate | prompt, HTTP, parsing, validation and persistence mixed; prompt/response are never stored; uses *cumulative process-local* counters as telemetry |
| `EvolutionLoopService` | `demo:evolution:auto` flag; `@Scheduled` trigger; `cycle()` = observe -> AI -> replay -> history (Redis list, 100 entries) | orchestration directly on `StringRedisTemplate` |
| `ReplayEvaluator` | pure 1-second FIFO queue simulation + 30 s drain; SLO reasons | already pure - moves to domain unchanged in behaviour |
| `AdmissionService` | Lua script (rate window, FIFO queue, tickets, expiry) | Redis adapter living next to business code |
| `TrafficObservationService` | per-second arrival counter `demo:arrivals:<epoch>` | Redis adapter |
| `TrafficController` | all endpoints, admin token check, Micrometer counters, simulated work | controller holds business flow and security |

Legacy endpoints (replaced by `/api/v1`, per decision): `GET /api/shop`, `/api/policy`, `/api/ai/status`,
`/api/admin/{evolution/status,evolution/history,evolution/cycle,evolution/automatic,ai/enabled,ai/propose,propose,evaluate,approve,rollback}`;
auth header `X-Demo-Admin` (kept, `Authorization: Bearer` also accepted).

### Evolution lifecycle today
1. Scheduler (`interval-ms`) or `POST /cycle` -> requires AI flag, no pending candidate, >=20 arrivals in 60 s (scheduler: >=120).
2. `AiEvolutionService.propose()` -> Ollama (`temperature 0.2`, 90 s timeout) -> PROPOSE / NO_CHANGE / INSUFFICIENT_DATA.
3. Envelope check (admission 1..1000, queue 1..10000), semantic no-op check, candidate saved (version+1, `EVOLVED`).
4. `ReplayEvaluator.compare` on the observed 60 s trace; invalid candidates are discarded.
5. Human `approve` -> push active onto history, activate. `rollback` pops history.

### Data flows
arrival -> Redis counter; admit -> Lua script -> Micrometer counter; AI -> Ollama; history -> Redis list; metrics -> Prometheus.

## 2. Target package structure (single Spring Boot module)

```
dev.demo.traffic
  domain/                 pure Java (no Spring, Jackson, Redis, HTTP)
    policy/  TrafficPolicy, PolicyMode, PolicyBounds, PolicyCandidate
    traffic/ TrafficObservation, TrafficMetrics, TrafficPoint, AdmissionDecision, AdmissionState, QueueTicket
    evolution/ EvolutionDecision, EvolutionExperiment, EvolutionEvent, EvolutionStage, EvaluationResult,
               ReplayEvaluator, GovernanceDecision, AiInvocation
  application/            use cases (plain Java) + ports
    port/out/  PolicyRepository, ExperimentRepository, TrafficMetricsPort, AiPolicyAdvisorPort, QueueRepository,
               FeatureFlagRepository, EvolutionEventPublisher, AiInvocationRepository, OperationalMetricsPort, JsonCodec
    usecase/   AdmitRequest, ObserveTraffic, ProposePolicy, EvaluatePolicy, ApprovePolicy, RejectPolicy,
               RollbackPolicy, EnableAi, EnableAutomaticEvolution, GetEvolutionHistory, AiInvocationQueries, ...
  adapter/
    in/web, in/scheduling      REST (v1), security, ProblemDetail, MDC filter, schedulers
    out/redis, out/ollama, out/jdbc, out/metrics, out/events
  config/                 Spring wiring of use cases
```
Dependency rule `adapter -> application -> domain` is enforced by an ArchUnit test.

## 3. Migration stages
1. Domain model + `ReplayEvaluator` move (port existing tests). 2. Ports and use cases. 3. Redis/Ollama/JDBC/Micrometer adapters.
4. `/api/v1` controllers + security + error handling; delete legacy classes. 5. AI invocation persistence (PostgreSQL via Flyway).
6. Logging/metrics. 7. Angular app (`frontend/`, Gradle Node plugin). 8. Docker Compose, Nginx proxy, Grafana. 9. Tests. 10. Docs.

## 4. Compatibility risks
- **Spring Boot 4 / Jackson 3** (`tools.jackson`), modular starters and test annotations differ from Boot 3.
- **Legacy endpoint removal** breaks `load/flash-sale.js`, `scripts/smoke.sh`, docs and any client (all updated).
- **Redis data**: policy keys keep their JSON shape; `demo:evolution:history` is superseded by a new experiment key; the ticket value in Redis changes from `waiting` to an enqueue timestamp (tickets in flight during upgrade are treated as expired).
- **Approval semantics** become stricter: a candidate must have a stored, valid evaluation bound to its fingerprint.
- **Ollama**: `/api/generate` is replaced by `/api/chat` (separate system and user prompt).
- Docker, Testcontainers and the Ollama model may be unavailable locally; those checks are reported as unverified.
