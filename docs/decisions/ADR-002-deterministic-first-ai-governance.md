# ADR-002: Deterministic-First Architecture and Human-in-the-Loop AI Governance

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

LLMs excel at summarisation, structured extraction, and explanation, but are non-deterministic, susceptible to prompt injection from untrusted inputs, and unsuitable as the sole authority for compliance, financial, architectural, or operational mutations.

## Alternatives Considered

1. **LLM-centric agent loop with direct write authority:** Simpler to wire initially, but fails auditability, reproducibility, and security requirements (prompt injection in a PR comment, claim document, source file, or supplier event could trigger unauthorised actions).
2. **Deterministic core with AI advisory/extraction layer and server-side human approval gates (Chosen):** All authoritative calculations, access control, rule violations, and state transitions run deterministically; AI outputs are schema-validated, evidence-linked, and clearly separated from verified facts.

## Decision

Across all Set 1 applications:

- **Engineering Intelligence MCP:** Read-only and write tools are strictly separated; write tools require write mode enabled AND a two-phase server-validated confirmation token bound to tool + arguments. Untrusted GitHub text is fenced and scanned for prompt injection.
- **Insurance Claims Intelligence:** Deterministic rules detect inconsistencies, missing evidence, and weighted risk indicators; domain invariants forbid AI actors from approving, rejecting, or paying claims.
- **Software Architecture Auditor:** Dependency graph construction, Tarjan SCC cycle detection, coupling metrics, and rule evaluation are 100% deterministic; AI remediation suggestions are stored separately and cannot modify verified findings.
- **Supply Chain Risk Intelligence:** Risk scores, shortage projections, correlation timelines, and idempotent/DLQ event handling are deterministic; AI investigations can only draft change proposals that require explicit human approval before execution.

## Trade-offs & Limitations

- Slightly higher upfront domain modelling effort compared to thin LLM prompt wrappers.
- Yields reproducible tests, clear security boundaries, and graceful degradation when AI providers are disabled or unavailable.
