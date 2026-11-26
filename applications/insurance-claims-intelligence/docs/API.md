# API Reference — Insurance Claims Intelligence (`/api/v1`)

All `/api/v1/*` endpoints require an `X-API-Key` header. Errors follow RFC 9457 Problem Details (`application/problem+json`) and echo `X-Correlation-ID`.

## Operational Endpoints (Unauthenticated)

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Liveness probe returning service name, version, and UTC timestamp |
| `GET` | `/ready` | Readiness probe checking database connectivity and configured AI/embedding providers |
| `GET` | `/metrics` | Prometheus text exposition format metrics (`claims_http_*`, `claims_ai_*`, `claims_domain_events_total`) |

## Claims Intelligence Endpoints (`/api/v1`)

| Method | Path | Allowed Roles | Description |
|---|---|---|---|
| `POST` | `/api/v1/claims` | `adjuster`, `reviewer`, `admin` | Create a new claim in `INTAKE` state |
| `GET` | `/api/v1/claims` | Any authenticated | List claims (paginated `limit`, `offset`) |
| `GET` | `/api/v1/claims/{claim_id}` | Any authenticated | Retrieve claim metadata, uploaded documents, and latest analysis |
| `POST` | `/api/v1/claims/{claim_id}/documents` | `adjuster`, `reviewer`, `admin` | Multipart upload (`file`, optional `document_type`); validates size & magic bytes, parses, chunks, embeds |
| `POST` | `/api/v1/claims/{claim_id}/analyze` | Any authenticated | Run deterministic + optional LLM extraction, entity resolution, coverage, conflicts, missing evidence, and advisory risk scoring |
| `GET` | `/api/v1/claims/{claim_id}/coverage` | Any authenticated | Fetch (or compute) deterministic policy coverage evaluation |
| `POST` | `/api/v1/claims/{claim_id}/search` | Any authenticated | Hybrid BM25 + vector RRF search with optional `document_types` filter |
| `POST` | `/api/v1/claims/{claim_id}/qa` | Any authenticated | Evidence-backed Q&A with abstention (`UNANSWERABLE`), injection fencing, and verified citations |
| `POST` | `/api/v1/claims/{claim_id}/submit-review` | `adjuster`, `reviewer`, `admin` | Transition claim into `IN_REVIEW` queue |
| `POST` | `/api/v1/claims/{claim_id}/review` | **Human `reviewer` or `admin` ONLY** | Record `APPROVE`, `REJECT`, `REQUEST_INFO`, or `AUTHORIZE_PAYMENT` with rationale and optional supervisor note |
| `GET` | `/api/v1/claims/{claim_id}/audit` | Any authenticated | Retrieve PII-redacted append-only claim audit trail |

## Standard RFC 9457 Error Codes

| HTTP Status | `error_code` | Trigger |
|---|---|---|
| `400` | `INVALID_DOCUMENT`, `INVALID_FILENAME` | Corrupt PDF/image, magic-byte mismatch, path traversal filename |
| `401` | `UNAUTHORIZED` | Missing or invalid `X-API-Key` |
| `403` | `AUTONOMOUS_DECISION_PROHIBITED` | AI/non-human actor attempted claim review decision |
| `403` | `UNAUTHORIZED_DECISION` | Human caller lacks required RBAC role |
| `404` | `CLAIM_NOT_FOUND` | Unknown `claim_id` |
| `409` | `INVALID_STATE_TRANSITION` | Illegal lifecycle state transition (e.g., deciding before `IN_REVIEW` or mutating `APPROVED`) |
| `413` | `PAYLOAD_TOO_LARGE` | Upload exceeds `MAX_UPLOAD_BYTES` |
| `415` | `UNSUPPORTED_DOCUMENT_FORMAT` | Unsupported file extension |
| `502` | `UPSTREAM_AI_UNAVAILABLE`, `AI_RESPONSE_VALIDATION_ERROR` | Upstream Ollama/OpenAI timeout, error, or malformed JSON |
