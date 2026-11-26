# ADR-001: Server-Enforced Two-Phase Cryptographic Write Confirmation

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

An MCP server connected to GitHub may be invoked by LLM-driven clients that simultaneously read untrusted repository content (PR bodies, issue comments, CI logs). Relying solely on client-side UI prompts or LLM system instructions to guard write operations (`write_create_issue`, `write_create_issue_comment`, `write_add_pull_request_labels`) leaves a single point of failure: a prompt-injected or buggy client can silently invoke write tools with altered parameters.

## Decision

We implement a **server-side, two-phase cryptographic confirmation protocol** enforced inside `ToolExecutionOrchestrator` and `ConfirmationService`, backed by SQLite (`confirmations` table):

1. **Default Deny:** `PERMISSION_MODE` defaults to `read-only`, rejecting all `WRITE` tools with `PERMISSION_DENIED`. Repository access also defaults to deny when `ALLOWED_REPOS` is empty unless `ALLOW_UNRESTRICTED_DEMO_REPOS=true` is explicitly opted in under `GITHUB_MODE=demo` + `DEPLOYMENT_MODE=local-stdio`.
2. **Deployment-Mode Trust Model (`local-stdio` vs `shared-server`):**
   - In `local-stdio` mode, confirmation is an in-process two-phase guard against accidental/prompt-injected mutations (`independentHumanAuth: false`); static `ACTOR_ID` is a local label only.
   - In `shared-server` mode, write challenges and consumptions require an authenticated per-request `(principalId, sessionId)` identity and a persistent `CONFIRMATION_SECRET` (>= 32 chars). Possession of a token alone cannot cross principals or sessions.
3. **Phase 1 (Challenge):** When `PERMISSION_MODE=write-with-confirmation`, calling any write tool without `confirmationToken` never calls `GitHubPort`. Instead, the server:
   - Computes `argsDigest = SHA-256(canonicalJson(args without confirmationToken))`.
   - Generates a 128-bit random `nonce` and computes `HMAC-SHA256(secret, nonce|toolName|actor|sessionId|deploymentMode|repo|argsDigest|expiresAt)` (`conf_v2.<nonce>.<hmac>`).
   - Persists a `PENDING` confirmation row (storing `SHA-256(token)` rather than raw token plaintext, plus `session_id` and `deployment_mode`) with bounded TTL (`CONFIRMATION_TTL_SECONDS`, default 300s).
   - Returns `CONFIRMATION_REQUIRED` containing `confirmationToken`, `expiresAt`, `actionPreview`, and explicit `trustModel` metadata.
4. **Phase 2 (Verify & Atomically Consume):** Re-invoking the tool with the exact same arguments and `confirmationToken`:
   - Verifies format, constant-time HMAC signature, token hash, actor, `sessionId`, `deploymentMode`, toolName, repository, `argsDigest`, and expiration.
   - Atomically marks the row consumed via `UPDATE confirmations SET status='CONSUMED', consumed_at=? WHERE nonce=? AND status='PENDING'`.
   - Executes the GitHub write only if `changes === 1`, propagating `AbortSignal` and recording any in-flight write timeout/transport drop as `AMBIGUOUS_WRITE_OUTCOME` (non-retryable) in `write_reconciliations` rather than blindly retrying.

## Consequences

- **Positive:** Prevents accidental writes, blocks parameter tampering between preview and execution, prevents single-use token replay (including concurrent replay), and preserves an auditable confirmation record in SQLite.
- **Negative:** Requires two round-trips for every write tool call and requires operators to configure a persistent `CONFIRMATION_SECRET` if pending confirmations must survive a server restart.
