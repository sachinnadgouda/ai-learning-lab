# ADR-001: Deterministic Graph Authority with Isolated Advisory AI

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

Software architecture governance gates (CI checks, SARIF uploads, regression diffs) require reproducible, auditable, tamper-proof results. Meanwhile, LLMs are useful for proposing concrete refactoring steps to resolve violations, but are non-deterministic and vulnerable to prompt injection embedded in scanned source code.

## Decision

1. **100% Deterministic Core:** Polyglot lexing/parsing, canonical `DependencyGraph` digests, iterative Tarjan SCC cycle detection, Robert C. Martin coupling metrics, rule evaluation, and `ScanDiff` execute without any LLM involvement.
2. **Type-Enforced Separation:** `AuditReport` splits output into two immutable fields:
   - `verifiedFindings: VerifiedFindingsEnvelope` (`verified = true` enforced in constructor)
   - `aiSuggestions: AiSuggestionsEnvelope` (`advisoryOnly = true` enforced in constructor)
3. **Graceful Degradation:** When `AI_PROVIDER=none`, or when the upstream AI service fails, times out, or emits invalid JSON, the audit still succeeds with authoritative `verifiedFindings` and an explicit `AiAdvisoryStatus`.
4. **Defense in Depth Against Prompt Injection:** All source snippets and violation metadata pass through `PromptInjectionSanitizer` and explicit `<<<UNTRUSTED_SOURCE_DATA:...>>>` fences before prompt assembly; suggestions targeting unknown violation IDs are discarded.

## Consequences

- **Positive:** Byte-identical scan digests across repeated runs, zero paid API keys required for CI, immune to prompt-injection-induced violation suppression.
- **Trade-off:** Static import-based graph analysis does not evaluate dynamic runtime reflection or dependency injection wiring; this is explicitly documented as a scope boundary.
