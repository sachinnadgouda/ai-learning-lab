# HTTP API Reference

Base URL (default): `http://127.0.0.1:8083`

## Authentication & Correlation

- All `/api/v1/**` endpoints require `X-API-Key: <AUDITOR_API_KEY>` (validated using constant-time byte comparison).
- `/health`, `/ready`, and `/metrics` are unauthenticated operational endpoints.
- Every response echoes `X-Correlation-ID` (propagated from the request header or generated as a UUID).

## Error Contract (RFC 9457 Problem Details)

All error responses use `Content-Type: application/problem+json` and never expose stack traces or secrets:

```json
{
  "type": "https://auditor.lab.dev/problems/path-not-allowed",
  "title": "Forbidden Scan Path",
  "status": 403,
  "detail": "Requested scan path 'src' is outside configured AUDITOR_ALLOWED_ROOTS",
  "instance": "/api/v1/scans",
  "correlationId": "corr-123"
}
```

| Status | Meaning |
|---|---|
| `400` | Malformed JSON body or missing required query parameter |
| `401` | Missing or invalid `X-API-Key` |
| `403` | Requested `repositoryPath` or `rulesPath` is outside `AUDITOR_ALLOWED_ROOTS` or attempts path traversal |
| `404` | Scan ID or route not found |
| `405` | HTTP method not allowed |
| `422` | Invalid YAML/JSON architectural ruleset (`RuleValidationException`) |
| `500` | Generic internal server error |

## Endpoints

### `POST /api/v1/scans`
Runs a deterministic scan and persists the snapshot.

**Request Body:**
```json
{
  "repositoryPath": "examples/violating-monolith",
  "rulesPath": "examples/rules.yaml",
  "includeAiAdvice": true
}
```
*(Alternatively provide inline `rulesYaml` or `rulesJson`, or omit to use the default hexagonal ruleset.)*

**Response:** `201 Created` returning full `AuditReport` with separated `verifiedFindings` and `aiSuggestions`.

### `GET /api/v1/scans?limit=50`
Returns recent `ScanSummary` records ordered newest first (`limit` clamped to `1..200`).

### `GET /api/v1/scans/{id}`
Returns the full persisted `AuditReport`.

### `GET /api/v1/scans/{id}/export?format=json|markdown|sarif|mermaid`
Exports the scan report with format-specific `Content-Type`:
- `json`: `application/json`
- `markdown`: `text/markdown; charset=utf-8`
- `sarif`: `application/sarif+json`
- `mermaid`: `text/plain; charset=utf-8`

### `GET /api/v1/scans/diff?base={baseScanId}&head={headScanId}`
Compares two scans and returns `ScanDiff` (`newViolations`, `resolvedViolations`, `unchangedViolations`, `couplingDeltas`, `cycleCountDelta`, `regressionDetected`).

### `POST /api/v1/scans/{id}/explain`
Invokes the configured AI advisor to populate or refresh `aiSuggestions` without mutating `verifiedFindings`.

### `GET /health`, `GET /ready`, `GET /metrics`
- `/health`: `200 OK` (`{"status":"ok"}`)
- `/ready`: `200 OK` when SQLite responds to `SELECT 1`, else `503 Service Unavailable`
- `/metrics`: Prometheus text exposition format (`text/plain; version=0.0.4`)
