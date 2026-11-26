# Observability

## AI prompt/response inspection

Every AI invocation is stored in PostgreSQL (`ai_invocation`): invocation ID, cycle ID, timestamp, model name and digest, system prompt, user prompt, **exact request payload sent to Ollama**, parameters (temperature, `num_predict`, timeout, retries), metrics context, history context, **raw response body**, parsed decision, proposed policy, rationale, validation status/errors, evaluation JSON, outcome, duration, token counts (when Ollama reports them) and errors/timeouts. The UI shows these stored values verbatim (no reconstruction).

- Admin only: `GET /api/v1/ai/invocations` (filters + pagination), `/{id}`, `/{id}/prompt`, `/{id}/response`, `/compare?left&right`.
- **Redaction** (`Redactor`) runs before persistence on prompts and responses: `Authorization`/bearer tokens, `X-Demo-Admin` values, `password|secret|token|api-key` assignments, e-mail addresses and long hex/base64 secrets. `redacted=true` records that something was replaced.
- **Retention**: `DEMO_RETENTION_AI` / `DEMO_RETENTION_EVENTS` (default 30 d), purged hourly by `RetentionScheduler`.

## Structured logging

SLF4J + Logback. Set `LOG_STRUCTURED_FORMAT=logstash` (Compose default) for JSON lines; empty gives plain text with the MDC pattern. MDC fields: `traceId`, `requestId` (from `traceparent` or generated), `evolutionCycleId`, `aiInvocationId`, `policyVersion`, `component`.

Logged: startup, Redis/Ollama connectivity, flag changes, observations, trigger decisions, AI request/response/parse, candidate creation, evaluation, governance, approval, activation, rollback, admission summaries and unexpected errors. Individual shop requests are **not** logged at INFO; `EvolutionScheduler` logs a traffic summary every 30 s. Admin actions go to the `audit` logger. Errors are `ProblemDetail` responses with `code` and `requestId`; stack traces never leave the server.

`GET /api/v1/diagnostics/events` (admin) exposes the persisted evolution/audit events filtered by time, level, cycle, AI invocation, policy version, component and text. Raw application logs are not exposed over HTTP.

## Metrics (Micrometer → Prometheus at `/actuator/prometheus`)

| Area | Metrics |
|---|---|
| Traffic | `demo_requests_incoming_total`, `demo_admission_total{state}`, `demo_backend_requests_total{outcome}`, `demo_backend_latency_seconds` (p50/p95/p99 + histogram), `demo_backend_concurrent` |
| Waiting room | `demo_queue_depth`, `demo_queue_unique_tickets`, `demo_queue_admitted_total`, `demo_queue_wait_seconds{quantile}`, `demo_queue_ticket_expired_total` |
| AI | `demo_ai_invocations_total{outcome}`, `demo_ai_latency_seconds`, `demo_ai_failures_total{type}`, `demo_ai_decisions_total{decision}`, `demo_ai_validation_rejections_total`, `demo_ai_tokens_total{kind}` |
| Evolution | `demo_evolution_cycles_started_total`, `demo_evolution_cycles_completed_total{status}`, `demo_evolution_evaluations_total{outcome}`, `demo_policy_approvals_total`, `demo_policy_rollbacks_total`, `demo_policy_active_version`, `demo_policy_active_admission_limit`, `demo_policy_active_max_queue` |

Labels are low-cardinality (states, outcomes, decisions). Invocation, request and customer IDs are never labels. Queue abandonment is only measurable as ticket expiry (`demo_queue_ticket_expired_total`); there is no separate abandonment signal.

**Observed vs simulated:** all series above are measured by the backend. Evaluation metrics (estimated p95, error fraction, wait) exist only in evaluation results and the UI, labelled **SIMULATED**.

## Grafana

`monitoring/grafana/` is provisioned automatically (datasource `Prometheus`, dashboard *Self-Evolving Traffic Demo*: RPS, throughput/5xx, latency percentiles, concurrency, queue, wait times, active policy, AI invocations/latency/decisions, evolution cycles, approvals/rollbacks). Rate-based panels use the Prometheus scrape (5 s); the Angular dashboard uses the backend's in-process per-second buckets.

## Tracing

Request correlation uses a `traceId`/`requestId` in MDC and responses; no distributed tracing backend is configured (a valid W3C `traceparent` header is honoured for the ID).
