# Repository Engineering Standards

All Set 1 applications adhere to the following baseline standards:

## 1. Architecture & Code Organisation
- Separate **domain** (pure models, invariants, algorithms), **application** (use cases, orchestration, ports), and **infrastructure** (HTTP/MCP transports, database adapters, external providers, CLI).
- Keep configuration externalised via environment variables with `.env.example`, validated at startup.
- Avoid hidden global mutable state; wire dependencies explicitly at the composition root.

## 2. Error Handling & API Contracts
- HTTP APIs use versioned prefixes (`/api/v1/...`) and return RFC 9457 Problem Details (`application/problem+json`) with correlation IDs and without stack traces or secret leakage.
- MCP tools return structured JSON payloads with explicit error codes (`INVALID_ARGUMENT`, `PERMISSION_DENIED`, `CONFIRMATION_REQUIRED`, `RATE_LIMITED`, `UPSTREAM_UNAVAILABLE`).

## 3. Reliability & Resource Safety
- Configure explicit connect/read timeouts on all outbound network calls.
- Implement bounded retries with exponential backoff + jitter for transient upstream failures and rate limits.
- Enforce bounded queues, pagination limits, and file-size / memory caps.
- Implement graceful shutdown and health/readiness endpoints.

## 4. Security & AI Governance
- Never commit secrets or `.env` files. Redact bearer tokens, API keys, and sensitive fields from logs and audit stores.
- Treat all external inputs (GitHub content, uploaded claim documents, scanned source repositories, supply-chain events) as untrusted data; fence them explicitly before any LLM prompt.
- Validate all structured LLM outputs against strict schemas and handle malformed responses safely.
- Enforce human confirmation/approval server-side for consequential mutations.

## 5. Observability
- Emit structured JSON logs with correlation/request IDs.
- Expose health/readiness endpoints and operational metrics (request duration, upstream errors, AI latency/usage, workflow counters).
- Persist audit records for sensitive actions and AI operations.

## 6. Testing & Evaluation
- Provide unit, integration, contract/API, and offline AI evaluation tests.
- Default CI must pass cleanly with zero paid live model API keys or private tokens.
- Pin or tightly constrain runtime and container image versions.
