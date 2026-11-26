"""Claim extraction, entity resolution, policy coverage, and risk analysis use case."""

from __future__ import annotations

from claims_intel.application.ports import (
    AuditRepository,
    ClaimAnalysisResult,
    ClaimRepository,
    LlmPort,
)
from claims_intel.application.qa_service import verify_citation_quote
from claims_intel.domain.errors import ClaimNotFoundError
from claims_intel.domain.injection import fence_evidence_chunks
from claims_intel.domain.models import (
    Actor,
    AuditEvent,
    ClaimStatus,
    EvidenceRef,
    ExtractedFact,
    FactClassification,
)
from claims_intel.domain.redaction import redact_text
from claims_intel.domain.rules import (
    assess_claim_risk,
    detect_inconsistencies_and_missing_evidence,
    evaluate_policy_coverage,
    extract_deterministic_facts,
    resolve_entities,
)


class ExtractionService:
    """Orchestrates deterministic extraction, optional LLM enrichment, and risk triage."""

    def __init__(
        self,
        *,
        claim_repo: ClaimRepository,
        audit_repo: AuditRepository,
        llm: LlmPort,
        enable_llm_enrichment: bool = True,
    ) -> None:
        self._claim_repo = claim_repo
        self._audit_repo = audit_repo
        self._llm = llm
        self._enable_llm_enrichment = enable_llm_enrichment

    def analyze_claim(
        self,
        *,
        claim_id: str,
        actor: Actor,
        correlation_id: str = "",
    ) -> ClaimAnalysisResult:
        claim = self._claim_repo.get_claim(claim_id)
        if claim is None:
            raise ClaimNotFoundError(claim_id)

        documents = self._claim_repo.list_documents(claim_id)
        indexed_chunks = self._claim_repo.list_indexed_chunks(claim_id)
        chunks = [ic.chunk for ic in indexed_chunks]
        chunk_by_id = {c.chunk_id: c for c in chunks}

        # Step 1: Authoritative deterministic fact extraction (EXTRACTED_FACT)
        facts = extract_deterministic_facts(claim_id, chunks)
        existing_pairs = {(f.field_name, (f.normalized_value or f.value).lower()) for f in facts}

        # Step 2: Optional LLM structured extraction (strictly verified against chunk_ids)
        llm_latency_ms = 0.0
        llm_tokens = 0
        if self._enable_llm_enrichment and chunks:
            fenced = fence_evidence_chunks(chunks[:20])
            llm_res = self._llm.extract_structured_facts(
                fenced_evidence=fenced,
                chunks=chunks[:20],
            )
            llm_latency_ms = llm_res.usage.latency_ms
            llm_tokens = llm_res.usage.prompt_tokens + llm_res.usage.completion_tokens

            for item in llm_res.output.items:
                # Reject any LLM fact referencing a non-existent chunk_id or ungrounded quote
                cited_chunk = chunk_by_id.get(item.chunk_id)
                if cited_chunk is None:
                    continue
                is_grounded, span_start, span_end = verify_citation_quote(
                    item.quote, cited_chunk
                )
                if not is_grounded:
                    continue
                pair = (item.field_name, item.value.strip().lower())
                if pair in existing_pairs:
                    continue
                existing_pairs.add(pair)
                # Epistemic guardrail: LLM facts are EXTRACTED_FACT only if quote appears verbatim
                classification = item.classification
                if (
                    classification == FactClassification.EXTRACTED_FACT
                    and item.quote.strip().lower() not in cited_chunk.text.lower()
                ):
                    classification = FactClassification.INFERRED

                facts.append(
                    ExtractedFact(
                        claim_id=claim_id,
                        field_name=item.field_name,
                        value=item.value.strip(),
                        normalized_value=item.value.strip().lower(),
                        classification=classification,
                        confidence=item.confidence,
                        source_method=f"llm:{self._llm.provider_name}",
                        evidence_refs=[
                            EvidenceRef(
                                chunk_id=cited_chunk.chunk_id,
                                document_id=cited_chunk.document_id,
                                filename=cited_chunk.filename,
                                page_number=cited_chunk.page_number,
                                char_start=span_start,
                                char_end=span_end,
                                quote=item.quote[:350],
                            )
                        ],
                    )
                )

        # Step 3: Deterministic entity resolution, policy coverage, inconsistencies, risk
        entity_links = resolve_entities(claim_id, facts)
        coverage = evaluate_policy_coverage(claim_id, facts)
        inconsistencies, missing_evidence = detect_inconsistencies_and_missing_evidence(
            claim_id=claim_id,
            documents=documents,
            chunks=chunks,
            facts=facts,
            entity_links=entity_links,
            coverage=coverage,
        )
        risk_assessment = assess_claim_risk(
            claim_id=claim_id,
            coverage=coverage,
            inconsistencies=inconsistencies,
            missing_evidence=missing_evidence,
        )

        # Enrich claim summary metadata from deterministic facts without overriding explicit values
        for link in entity_links:
            if link.entity_type == "policy_number" and not claim.policy_number:
                claim.policy_number = link.canonical_value
            elif link.entity_type == "claimant_name" and not claim.claimant_name:
                claim.claimant_name = link.canonical_value
        for fact in facts:
            if fact.field_name == "incident_type" and not claim.incident_type:
                claim.incident_type = fact.value

        if claim.status in {ClaimStatus.INTAKE, ClaimStatus.EXTRACTED, ClaimStatus.NEEDS_INFO}:
            claim.mark_extracted()
        claim = self._claim_repo.save_claim(claim)

        self._claim_repo.save_analysis(
            claim_id=claim_id,
            facts=facts,
            entity_links=entity_links,
            coverage=coverage,
            inconsistencies=inconsistencies,
            missing_evidence=missing_evidence,
            risk_assessment=risk_assessment,
        )

        self._audit_repo.record(
            AuditEvent(
                claim_id=claim_id,
                event_type="CLAIM_ANALYZED",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=redact_text(
                    f"Analyzed claim {claim_id}: {len(facts)} facts, "
                    f"coverage={coverage.verdict.value}, "
                    f"inconsistencies={len(inconsistencies)}, "
                    f"missing_evidence={len(missing_evidence)}, "
                    f"advisory_risk={risk_assessment.total_score}/100 "
                    f"({risk_assessment.severity.value})."
                ),
                metadata={
                    "fact_count": len(facts),
                    "entity_link_count": len(entity_links),
                    "coverage_verdict": coverage.verdict.value,
                    "inconsistency_count": len(inconsistencies),
                    "missing_evidence_count": len(missing_evidence),
                    "risk_score": risk_assessment.total_score,
                    "risk_severity": risk_assessment.severity.value,
                    "llm_provider": self._llm.provider_name,
                    "llm_latency_ms": round(llm_latency_ms, 2),
                    "llm_tokens": llm_tokens,
                },
            )
        )

        return ClaimAnalysisResult(
            claim=claim,
            facts=facts,
            entity_links=entity_links,
            coverage=coverage,
            inconsistencies=inconsistencies,
            missing_evidence=missing_evidence,
            risk_assessment=risk_assessment,
        )
