# MCP Interface Specification

Server identity: `engineering-intelligence-mcp` (`v1.0.0`), transported via JSON-RPC over `stdio` (`StdioServerTransport`) or `InMemoryTransport` in tests.

## 1. Response Envelope

Every tool returns JSON inside a standard MCP `text` content block with `isError: !response.ok`:

```json
{
  "ok": true,
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "toolName": "analyze_pull_request",
  "permissionClass": "READ",
  "data": { "...": "..." }
}
```

Error envelope (never includes stack traces or secrets):

```json
{
  "ok": false,
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "toolName": "write_add_pull_request_labels",
  "permissionClass": "WRITE",
  "error": {
    "code": "CONFIRMATION_REQUIRED",
    "message": "Explicit human confirmation required...",
    "retryable": false,
    "details": {
      "confirmationToken": "conf_v1.<nonce>.<hmac>",
      "expiresAt": "2026-11-26T15:05:00.000Z",
      "actionPreview": "Add labels [security-review-required] to acme-eng/payments-platform#142"
    }
  }
}
```

### Machine-Readable Error Codes

- `INVALID_ARGUMENT` (400, non-retryable)
- `AUTH_FAILED` (401, non-retryable)
- `PERMISSION_DENIED` (403, non-retryable)
- `NOT_FOUND` (404, non-retryable)
- `CONFIRMATION_REQUIRED` (409, non-retryable — requires approval + retry with `conf_v2` token)
- `CANCELLED` (499, non-retryable — cancelled/timed out before upstream write was sent)
- `AMBIGUOUS_WRITE_OUTCOME` (504, non-retryable — write request was already dispatched before timeout/drop; do not blindly retry; reconcile with `read_reconcile_write_outcome`)
- `RATE_LIMITED` (429, retryable)
- `UPSTREAM_UNAVAILABLE` (502, retryable)
- `CIRCUIT_OPEN` (503, retryable)
- `INTERNAL_ERROR` (500, non-retryable)

## 2. Read Tools (`readOnlyHint: true`)

1. **`analyze_pull_request`**
   - Inputs: `{ owner, repo, pullNumber, includeAiSummary?: boolean }`
   - Output: `PullRequestAnalysis` (`riskScore`, `riskLevel`, `riskFlags`, `codeOwners`, `unownedFiles`, `reviewSummary`, `checkSummary`, `recommendations`, `fencedTitle`, `fencedBody`, `injectionWarnings`, optional `aiSummary`).
2. **`investigate_ci_failures`**
   - Inputs: `{ owner, repo, branch?: string, maxRuns?: number (1..50), includeAiSummary?: boolean }`
   - Output: `CiFailureDiagnosis` (`analyzedRuns`, `failedRuns`, `failureRatePercent`, `recurringFailures`, `recentSuspiciousCommits`, `recommendedActions`, `injectionWarnings`, optional `aiSummary`).
3. **`read_engineering_activity`**
   - Inputs: `{ owner, repo, days?: number (1..90) }`
   - Output: `EngineeringActivitySummary` (`totals`, `topAuthors`, `recentCommits`, `mergedPullRequests`, `releases`, `injectionWarnings`).
4. **`read_repo_insights`**
   - Inputs: `{ owner, repo, windowDays?: number (1..180), stalePrDays?: number (1..90) }`
   - Output: `RepoHealthInsights` (`prMetrics`, `contributorConcentration`, `ciHealth`, `issueBacklog`, `releaseCadence`, `healthScore`, `healthStatus`, `highlights`, `risks`).
5. **`read_search_issues_and_prs`**
   - Inputs: `{ owner, repo, state?: 'open'|'closed'|'all', type?: 'all'|'issue'|'pr', query?: string, labels?: string[], page?: number, perPage?: number }`
   - Output: `SearchIssuesAndPrsResult` (`filters`, `pagination`, `items` with fenced title/body excerpt, `injectionWarnings`).
6. **`read_reconcile_write_outcome`**
   - Inputs: `{ owner, repo, correlationId }`
   - Output: `WriteReconciliationResult` (`correlationId`, `toolName`, `repository`, `reconciledStatus`: `'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM'`, `upstreamUrl`, `evidence`, `nextStep`). Reconciles an earlier `AMBIGUOUS_WRITE_OUTCOME` by inspecting GitHub for the embedded `<!-- mcp-op:<correlationId> -->` marker or expected PR labels.

## 3. Write Tools (`readOnlyHint: false`, 2-Phase Confirmed)

7. **`write_create_issue_comment`**: `{ owner, repo, issueNumber, body, confirmationToken? }`
8. **`write_create_issue`**: `{ owner, repo, title, body, labels?, confirmationToken? }`
9. **`write_add_pull_request_labels`**: `{ owner, repo, pullNumber, labels, confirmationToken? }`

## 4. MCP Resources

- `engineering://repo/{owner}/{repo}/overview` — Template resource returning sanitized repo metadata, open PR count, and latest release (enforces `ALLOWED_REPOS`).
- `engineering://audit/recent` — Latest 25 redacted SQLite audit entries.
- `engineering://health` — Current server health status and in-memory metrics snapshot.
