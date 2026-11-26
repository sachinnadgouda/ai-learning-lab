# ADR-001: Human-in-the-Loop Claims Governance & Deterministic Risk Triage

- **Status:** Accepted
- **Date:** 2026-11-26

## Context

Insurance claim approvals, rejections, and payout authorizations carry legal, financial, and regulatory liability. Allowing an LLM or automated agent to execute claim decisions introduces unacceptable risks of prompt injection, hallucinated coverage interpretations, and non-reproducible outcomes.

## Decision

1. **Domain-Enforced Human-Only Decision Invariant:** `Claim.record_review_decision(actor, decision, rationale)` enforces `actor.actor_type == ActorType.HUMAN` and `actor.role in {Role.REVIEWER, Role.ADMIN}`. Any attempt by `ActorType.AI_SYSTEM` or `ActorType.SERVICE` raises `AutonomousDecisionProhibitedError` (HTTP `403`) and is persisted as a `REVIEW_DECISION_BLOCKED` audit event.
2. **Deterministic Authoritative Rules:** Policy coverage lookup (`COVERED`/`PARTIAL`/`EXCLUDED`/`UNVERIFIED`), cross-document entity conflict detection, missing-evidence rules, and weighted risk scoring (`0..100`, `advisory_only=True`) are implemented as pure deterministic Python functions without LLM involvement.
3. **Epistemic Transparency:** Every `ExtractedFact` explicitly carries `FactClassification` (`EXTRACTED_FACT` | `INFERRED` | `UNCERTAIN`), and every Q&A answer must cite verified `chunk_id`s from the retrieved set or abstain with `UNANSWERABLE`.

## Consequences

- **Positive:** 100% reproducible risk triage, verifiable auditability, resilience to document prompt injection, and full offline testability.
- **Trade-off:** Requires explicit human reviewer sign-off before terminal `APPROVED`/`REJECTED` transitions and maintains a separate deterministic extraction ruleset alongside optional LLM enrichment.
