# REST API (`/api/v1`)

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`. Errors are RFC 9457 `ProblemDetail` JSON with `code` and `requestId`.

**Auth:** every endpoint except the ones marked *public* needs `X-Demo-Admin: <token>` (or `Authorization: Bearer <token>`); missing/wrong token → `401`. Legacy `/api/*` endpoints from v0.1 were removed (see [REFACTORING_CHANGELOG.md](REFACTORING_CHANGELOG.md)).

| Method & path | Description |
|---|---|
| `GET /shop` *(public)* | Waiting-room protected simulated purchase. `200` admitted, `202` queued `{ticket}`, poll with `?ticket=`, `429` rejected, `410` ticket expired |
| `GET /system/health` *(public)* | `{status, redis, postgres, ollama}` (up/down only) |
| `GET /system/settings` | effective runtime settings, persistence behaviour, `restartRequired` map (no secrets) |
| `GET /dashboard/summary` | metrics, traffic mode, active/candidate policy, flags, Ollama status |
| `GET /traffic/metrics?windowSeconds=60` | observed metrics (RPS, latency p50/95/99, 5xx rate, concurrency, queue) |
| `GET /traffic/timeseries?seconds=300&stepSeconds=5` | per-bucket counts (divide by `stepSeconds` for RPS) |
| `GET /ai/status` | flags + Ollama connectivity/models |
| `GET /ai/invocations` | paginated list; params `page,size,from,to,decision,status,validation,cycleId,model,q` |
| `GET /ai/invocations/{id}` | full record: prompt, raw response, recommendation, independent validation, timing |
| `GET /ai/invocations/{id}/prompt` · `/response` | prompt block / response block |
| `GET /ai/invocations/compare?left=&right=` | both records + field differences |
| `GET /evolution/cycles` · `/cycles/{id}` · `/cycles/{id}/timeline` | experiments and lifecycle events |
| `POST /evolution/cycles` | run one cycle now (needs AI flag) |
| `GET /policies` | active, candidate, evaluation, `approvable`, history |
| `POST /policies/candidates/deterministic` | create 80 % candidate (no AI) |
| `POST /policies/candidate/evaluate` | evaluate pending candidate |
| `POST /policies/candidate/approve` `{candidateFingerprint, reason}` | approve; `409` if not evaluated/failed/stale/mismatching |
| `POST /policies/candidate/reject` `{reason}` · `POST /policies/rollback` `{reason}` | reject / roll back |
| `GET /flags` · `PUT /flags/ai` `{enabled}` · `/flags/automatic` `{enabled}` · `/flags/model` `{model}` · `/flags/trigger-threshold` `{arrivals}` | feature flags and thresholds |
| `GET /diagnostics/events` | filters `from,level,cycleId,aiInvocationId,policyVersion,component,q,page,size` |

Pagination envelope: `{items, page, size, totalItems, totalPages}`.

## Examples

```bash
T=$DEMO_ADMIN_TOKEN
curl -H "X-Demo-Admin: $T" localhost:8080/api/v1/policies
curl -X PUT -H "X-Demo-Admin: $T" -H 'Content-Type: application/json' -d '{"enabled":true}' localhost:8080/api/v1/flags/ai
curl -X POST -H "X-Demo-Admin: $T" localhost:8080/api/v1/evolution/cycles
curl -H "X-Demo-Admin: $T" 'localhost:8080/api/v1/ai/invocations?decision=PROPOSE&size=5'
curl -X POST -H "X-Demo-Admin: $T" -H 'Content-Type: application/json' \
  -d '{"candidateFingerprint":"2:EVOLVED:45:600:30","reason":"reviewed"}' localhost:8080/api/v1/policies/candidate/approve
```

Fingerprint format: `version:mode:admissionPerSecond:maxQueue:simulatedWorkMs` of the candidate shown by `GET /policies`.
