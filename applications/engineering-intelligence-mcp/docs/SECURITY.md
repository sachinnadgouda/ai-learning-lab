# Security & Threat Model

## 1. Security Posture Summary

This server is designed under a **zero-trust GitHub content** assumption: any text authored inside a GitHub repository (PR titles, bodies, reviews, issue descriptions, commit messages, file paths, workflow logs) may be controlled by an external or compromised contributor.

## 2. Threat Matrix & Implemented Controls

| Threat | Control | Implementation |
|---|---|---|
| Unauthorized repository mutation by AI agent | Read-only default + explicit tool classification | `PERMISSION_MODE=read-only` default in `env.ts` & `PermissionPolicy` |
| Unintended write or argument tampering after approval | Cryptographic two-phase confirmation | `ConfirmationService` (HMAC-SHA256 + canonical SHA-256 args digest + TTL + atomic SQLite single-use consumption) |
| Confirmation token replay | Atomic `UPDATE ... WHERE status = 'PENDING'` | `SqliteAuditStore.consumeConfirmation` |
| Access to out-of-scope repositories | Default-deny repository allowlist (`owner/repo` or `owner/*`) enforced on tools & resources | `ALLOWED_REPOS` in `PermissionPolicy` (`GITHUB_MODE=live` fails fast when empty; unrestricted demo requires explicit `ALLOW_UNRESTRICTED_DEMO_REPOS=true` + `GITHUB_MODE=demo` + `DEPLOYMENT_MODE=local-stdio`) |
| Shared-server actor/session spoofing on write confirmation | Deployment-mode trust boundary (`local-stdio` vs `shared-server`) | `ConfirmationService` (`conf_v2` binds `actor`, `sessionId`, `deploymentMode`, `argsDigest`; `shared-server` rejects static `ACTOR_ID` and requires authenticated per-request principal+session and persistent `CONFIRMATION_SECRET`) |
| Duplicate or unknown GitHub mutation after timeout/network drop | AbortSignal cancellation + non-retryable ambiguous write handling + reconciliation | `ToolExecutionOrchestrator`, `GitHubRestClient`, `SqliteAuditStore` (`write_reconciliations`), and `read_reconcile_write_outcome` tool |
| Indirect prompt injection via PR/issue/log text | Two-pass multi-category detector + sanitizer + XML-style data fences + breakout escaping | `PromptInjectionGuard` + defensive LLM system prompt |
| Secret leakage in logs, errors, or audit DB | Recursive secret redactor (`ghp_`, `github_pat_`, `Bearer`, `sk-`, `conf_v1`/`conf_v2`, sensitive keys) | `secretRedactor.ts` applied in `JsonStderrLogger`, `ToolExecutionOrchestrator`, `GitHubRestClient` |
| Path traversal / URL injection via `owner`/`repo` | Strict slug regex (`^[a-zA-Z0-9._-]{1,100}$`) + `encodeURIComponent` | `schemas.ts` & `GitHubRestClient.ts` |
| Unauthenticated `/metrics` exposure in shared deployments | Loopback default (`127.0.0.1`), `METRICS_ENABLED` toggle, and constant-time `METRICS_AUTH_TOKEN` enforcement | `HealthHttpServer` & `loadConfig()` fail-fast guard in `env.ts` |
| Container privilege escalation / network exposure | Non-root `node` user, read-only rootfs, `cap_drop: [ALL]`, `no-new-privileges`, `127.0.0.1` port binding | `Dockerfile` & `compose.yaml` |

## 3. Confirmation Trust Boundary (`local-stdio` vs `shared-server`)

- **`DEPLOYMENT_MODE=local-stdio` (default):** Single-user local process where the MCP host spawns the server over stdio. Confirmation tokens (`conf_v2`) prevent accidental or prompt-injected silent mutations, **not** independent cryptographic human identity; challenge payloads explicitly state `independentHumanAuth: false` and static `ACTOR_ID` is treated only as a local process label.
- **`DEPLOYMENT_MODE=shared-server`:** Multi-user or shared deployment. The server rejects static `ACTOR_ID` fallback for write operations, requires an authenticated per-request `(principalId, sessionId)` identity from the caller layer, binds `conf_v2` signatures and SQLite records to both `actor` and `sessionId`, and fails fast at startup unless a persistent `CONFIRMATION_SECRET` (>= 32 chars) is configured.

## 4. Token & Secret Handling Rules

- Never commit `.env` or real `GITHUB_TOKEN` values.
- Use fine-grained GitHub PATs scoped strictly to target repositories and read-only permissions unless confirmed writes are explicitly required.
- Set a strong `CONFIRMATION_SECRET` (>= 32 random chars) in production deployments where confirmation tokens must remain verifiable across restarts.
- Raw confirmation tokens are **only** returned once in the live `CONFIRMATION_REQUIRED` challenge response and are always redacted in `audit_logs` and `stderr` logs.
