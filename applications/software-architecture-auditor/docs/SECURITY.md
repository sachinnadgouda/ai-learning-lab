# Security & Threat Model

## Trust Boundaries

1. **Scanned Source Repositories (Untrusted Input):** Source files, comments, file paths, and identifiers are treated as adversarial input.
2. **HTTP API Boundary:** External callers are untrusted until authenticated with `X-API-Key` and restricted to `AUDITOR_ALLOWED_ROOTS`.
3. **LLM Provider Boundary:** Model outputs are treated as untrusted advisory text requiring strict JSON schema validation.

## Mitigations Implemented

| Threat | Mitigation | Implementation |
|---|---|---|
| Unauthorized API scans / data access | Server-side constant-time `X-API-Key` verification on `/api/v1/**`; minimum 16-char key enforced at server startup | `HttpServer.kt`, `Config.kt` |
| Arbitrary server filesystem reads & path traversal | Canonical `toRealPath()` prefix enforcement against `AUDITOR_ALLOWED_ROOTS` on both `repositoryPath` and `rulesPath` | `RepositoryScanner.validateAllowedRoot`, `HttpServer.kt` |
| Symlink escape attacks inside scanned repos | Symlinks resolving outside canonical scan root are rejected (skipped with warning or hard-failed in strict mode) and never read | `RepositoryScanner.scan` |
| Resource exhaustion / DoS via huge repos | Hard caps on `maxFiles`, `maxFileBytes`, `maxDepth`, and ignored build/dependency directories | `ScannerLimits` |
| Prompt injection hidden in code comments/strings | Regex + control-char neutralization (`PromptInjectionSanitizer`), explicit `<<<UNTRUSTED_SOURCE_DATA:...>>>` fences, fence-escape redaction | `PromptInjectionSanitizer.kt`, `HttpAiAdvisor.kt` |
| AI tampering with compliance/audit results | Separate immutable `verifiedFindings` vs `aiSuggestions` envelopes; `verified=true` and `advisoryOnly=true` invariants; unknown target IDs dropped | `RemediationModels.kt` |
| YAML deserialization gadgets / billion laughs | SnakeYAML `SafeConstructor` with `maxAliasesForCollections = 20` and duplicate keys disabled | `ArchitectureRules.kt` |
| Secret leakage in logs or audit trail | `SecretRedactor` masks bearer tokens, API keys, and passwords before persisting `audit_events`; RFC 9457 error responses omit stack traces | `Observability.kt`, `SqliteScanRepository.kt` |
| Container privilege escalation | Multi-stage non-root `auditor` user, `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges:true`, localhost port binding (`127.0.0.1:8083`) | `Dockerfile`, `compose.yaml` |
