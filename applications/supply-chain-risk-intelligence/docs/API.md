# REST API Specification

Base URL: `http://127.0.0.1:8080`
Content-Type: `application/json` (errors: `application/problem+json` per RFC 9457)
Authentication Header: `X-API-Key: <key>` (required on all `/api/v1/*` routes)
Optional Audit Headers: `X-Correlation-ID`, `X-Actor-ID`, `X-Actor-Type`

## Role Matrix

| Role | Permitted Operations |
|---|---|
| `operator` | Read all resources, seed/upsert entities, ingest events, run AI investigations, create/execute approved proposals, replay DLQ |
| `approver` | Read all resources, ingest events, run AI investigations, create/approve/reject/execute approved proposals |
| `admin` | All operations |

## Endpoints

### Operational
- `GET /health` -> `200 OK` (`{"status":"ok","time":"..."}`)
- `GET /ready` -> `200 OK` (`status:"ready"`) or `503 Service Unavailable` (`status:"degraded"` if SQLite or Redis broker fails `Ping`)
- `GET /metrics` -> `200 OK` Prometheus text exposition v0.0.4

### Entities
- `GET /api/v1/suppliers`, `GET /api/v1/suppliers/{id}`, `POST /api/v1/suppliers`
- `GET /api/v1/routes`, `GET /api/v1/routes/{id}`, `POST /api/v1/routes`
- `GET /api/v1/shipments`, `GET /api/v1/shipments/{id}`, `POST /api/v1/shipments`
- `GET /api/v1/inventory`, `GET /api/v1/inventory/{sku}`, `POST /api/v1/inventory`

### Event Ingestion
- `POST /api/v1/events`
  - Accepts a single `SupplyEvent` JSON object, `{"events": [...]}`, or a JSON array `[...]` (1–500 events).
  - Event types: `SHIPMENT_DELAYED`, `SUPPLIER_DISRUPTION`, `ROUTE_CONGESTION`, `INVENTORY_CONSUMED`, `INVENTORY_REPLENISHED`, `SUPPLIER_RECOVERED`.
  - Responses: `202 Accepted` with `event_ids` and `correlation_id`, or `429 Too Many Requests` (`Retry-After: 1`) if bounded queue is full.

### Risk & Correlation
- `GET /api/v1/risks/suppliers` — Sorted deterministic supplier risk scores with explicit contributing factors.
- `GET /api/v1/risks/routes` — Sorted deterministic route risk scores.
- `GET /api/v1/risks/shortages` — Inventory shortage alerts sorted by severity & days of cover.
- `GET /api/v1/incidents` — Correlated cross-entity incidents.
- `GET /api/v1/incidents/{id}/timeline` — Chronologically sorted event timeline, shortages, and ranked alternative recommendations.
- `POST /api/v1/incidents/{id}/investigate` — Runs fenced AI investigation (`{"draft_proposal": true}` optional, default `true`). Always degrades gracefully to deterministic summary if AI is unavailable.

### Human-in-the-Loop Change Proposals
- `GET /api/v1/proposals` — List all change proposals.
- `POST /api/v1/proposals` — Create a manual `PENDING_APPROVAL` proposal.
- `POST /api/v1/proposals/{id}/approve` — Human `approver`/`admin` approves proposal (`{"comment":"..."}`). Returns `403` for `operator` or `AI_SYSTEM`.
- `POST /api/v1/proposals/{id}/reject` — Human `approver`/`admin` rejects proposal (`{"reason":"..."}`).
- `POST /api/v1/proposals/{id}/execute` — Executes an `APPROVED` proposal. Returns `409 Conflict` if not approved, `403 Forbidden` if called by `AI_SYSTEM`.

### Recovery & Audit
- `GET /api/v1/dlq` — List dead-lettered events.
- `POST /api/v1/dlq/{id}/replay` — Reset DLQ claim and re-enqueue event for processing (`202 Accepted`).
- `GET /api/v1/audit?limit=100` — Persistent audit trail.
- `GET /api/v1/alerts?limit=100` — Operational alert delivery log.
