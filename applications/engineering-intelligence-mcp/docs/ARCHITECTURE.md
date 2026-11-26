# Architecture — Engineering Intelligence MCP Server

## 1. Hexagonal Layer Boundaries

| Layer | Directory | Responsibilities | Allowed Dependencies |
|---|---|---|---|
| **Domain** | `src/domain/` | Entities, error taxonomy, PR risk scoring, CI failure diagnosis, repo insights math, CODEOWNERS parser, permission policy, confirmation service, prompt-injection guard, secret redactor | Node built-in `node:crypto` only |
| **Application** | `src/application/` | Outbound port interfaces, Zod input schemas, `ToolExecutionOrchestrator`, 8 use cases | `src/domain/`, `zod`, `node:crypto` |
| **Infrastructure** | `src/infrastructure/` | MCP SDK server/resources, GitHub REST & Demo adapters, `node:sqlite` audit/confirmation store, HTTP AI summarizer, JSON stderr logger, Prometheus metrics, health HTTP server, env config loader | `src/application/`, `src/domain/`, SDK & Node stdlib |
| **Entrypoints & Evals** | `src/index.ts`, `src/cli/`, `src/evals/` | Composition root, stdio MCP CLI demo client, offline evaluation runner | All layers |

## 2. Execution Pipeline (`ToolExecutionOrchestrator`)

Every MCP tool call flows through a single deterministic pipeline:

1. **Correlation ID:** Generates a UUIDv4 `correlationId` attached to logs, tool response, and SQLite audit row.
2. **Input Validation:** Parses raw arguments via strict Zod schemas (including safe `owner`/`repo` slug regexes preventing path traversal).
3. **Server-Side Authorization (`PermissionPolicy`):** Checks `ALLOWED_REPOS` allowlist and enforces `PERMISSION_MODE` (`read-only` unconditionally blocks `WRITE` tools).
4. **Two-Phase Confirmation (`ConfirmationService`):** For `WRITE` tools, either issues a `CONFIRMATION_REQUIRED` challenge or verifies & atomically consumes a single-use HMAC token bound to canonical argument SHA-256 digest.
5. **Bounded Execution:** Executes the use case under a strict timeout (`runWithTimeout`).
6. **Observability & Audit (`finally`):** Records Prometheus counters/durations, logs structured JSON to `stderr`, and appends a secret-redacted row to SQLite `audit_logs` regardless of outcome (`SUCCESS`, `DENIED`, `CONFIRMATION_REQUIRED`, `ERROR`).

## 3. Data Flow — Prompt-Injection Defense

Untrusted GitHub content (PR title/body/reviews/file paths, issue titles/bodies, commit messages, CI job log excerpts, repo description) passes through `PromptInjectionGuard.inspectAndFence` in two passes:
- **Pass 1 (Detection):** Strips invisible/bidi Unicode obfuscation and scans the full normalized snapshot against all rule categories so no redaction masks a secondary attack vector.
- **Pass 2 (Sanitization & Fencing):** Redacts malicious HTML comments and control directives, escapes any embedded `</untrusted_github_content>` breakout tag, and wraps content in `<untrusted_github_content source="..." field="...">...</untrusted_github_content>`.

## 4. Resilience Architecture (`GitHubRestClient`)

- **Timeout:** `AbortController` timer per HTTP call.
- **Pagination:** Parses RFC 5988 `Link: <...>; rel="next"` headers capped by both `GITHUB_MAX_PAGES` and caller `limit`.
- **Retries:** Exponential backoff + jitter on `429`, rate-limited `403`, `5xx`, and network/timeout errors for `GET` requests. Never retries `401`, permission `403`, `404`, `422`, or mutating `POST` requests.
- **Circuit Breaker:** `CLOSED` -> `OPEN` after consecutive upstream 5xx/network failures; transitions to `HALF_OPEN` after cooldown; returns `CIRCUIT_OPEN` immediately while open.
