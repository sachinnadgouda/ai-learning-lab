"""Unit tests for extraction, entity resolution, policy coverage, conflicts, and risk math."""

from __future__ import annotations

from decimal import Decimal

from claims_intel.domain.models import (
    CoverageVerdict,
    DocumentRecord,
    DocumentType,
    EvidenceChunk,
    Severity,
)
from claims_intel.domain.rules import (
    assess_claim_risk,
    detect_inconsistencies_and_missing_evidence,
    evaluate_policy_coverage,
    extract_deterministic_facts,
    resolve_entities,
)


def _doc(doc_id: str, claim_id: str, filename: str, doc_type: DocumentType) -> DocumentRecord:
    return DocumentRecord(
        document_id=doc_id,
        claim_id=claim_id,
        filename=filename,
        content_sha256="a" * 64,
        mime_type="text/plain",
        size_bytes=512,
        document_type=doc_type,
    )


def _chunk(
    doc_id: str, claim_id: str, filename: str, doc_type: DocumentType, text: str
) -> EvidenceChunk:
    return EvidenceChunk(
        chunk_id=f"{doc_id}:p1:c0",
        claim_id=claim_id,
        document_id=doc_id,
        filename=filename,
        document_type=doc_type,
        page_number=1,
        char_start=0,
        char_end=len(text),
        text=text,
    )


def test_clean_claim_is_covered_and_low_risk() -> None:
    claim_id = "CLM-CLEAN"
    docs = [
        _doc("d1", claim_id, "claim_form.txt", DocumentType.CLAIM_FORM),
        _doc("d2", claim_id, "policy_schedule.txt", DocumentType.POLICY_SCHEDULE),
        _doc("d3", claim_id, "invoice.json", DocumentType.INVOICE),
    ]
    chunks = [
        _chunk(
            "d1",
            claim_id,
            "claim_form.txt",
            DocumentType.CLAIM_FORM,
            "Policy Number: POL-2026-1001\nClaimant Name: Elena Vance\n"
            "Incident Date: 2026-05-14\nIncident Type: Hail Windshield\nClaimed Amount: $3,250.00",
        ),
        _chunk(
            "d2",
            claim_id,
            "policy_schedule.txt",
            DocumentType.POLICY_SCHEDULE,
            "Policy Number: POL-2026-1001\nPolicyholder: Elena Vance\n"
            "Effective Period: 2026-01-01 to 2026-12-31\n"
            "Covered Perils: hail, windshield, collision\nExclusions: racing\n"
            "Deductible: $500.00\nCoverage Limit: $50,000.00",
        ),
        _chunk(
            "d3",
            claim_id,
            "invoice.json",
            DocumentType.INVOICE,
            "Vendor: Alpine AutoGlass\nClaimant Name: Elena Vance\nInvoice Total: $3,250.00",
        ),
    ]

    facts = extract_deterministic_facts(claim_id, chunks)
    links = resolve_entities(claim_id, facts)
    coverage = evaluate_policy_coverage(claim_id, facts)
    inconsistencies, missing = detect_inconsistencies_and_missing_evidence(
        claim_id=claim_id,
        documents=docs,
        chunks=chunks,
        facts=facts,
        entity_links=links,
        coverage=coverage,
    )
    risk = assess_claim_risk(
        claim_id=claim_id,
        coverage=coverage,
        inconsistencies=inconsistencies,
        missing_evidence=missing,
    )

    assert coverage.verdict == CoverageVerdict.COVERED
    assert coverage.estimated_payable == Decimal("2750.00")
    assert inconsistencies == []
    assert missing == []
    assert risk.total_score == 0
    assert risk.severity == Severity.LOW
    assert risk.advisory_only is True


def test_high_risk_claim_detects_all_conflicts_missing_police_and_high_score() -> None:
    claim_id = "CLM-RISK"
    docs = [
        _doc("d1", claim_id, "claim_form.pdf", DocumentType.CLAIM_FORM),
        _doc("d2", claim_id, "policy_schedule.txt", DocumentType.POLICY_SCHEDULE),
        _doc("d3", claim_id, "invoice.json", DocumentType.INVOICE),
    ]
    chunks = [
        _chunk(
            "d1",
            claim_id,
            "claim_form.pdf",
            DocumentType.CLAIM_FORM,
            "Policy Number: POL-2026-7742\nClaimant Name: Marcus Thorne\n"
            "Incident Date: 2026-01-05\nIncident Type: Collision Theft\nClaimed Amount: $24,500.00",
        ),
        _chunk(
            "d2",
            claim_id,
            "policy_schedule.txt",
            DocumentType.POLICY_SCHEDULE,
            "Policy Number: POL-2026-7742\nPolicyholder: Marcus Thorne\n"
            "Effective Period: 2026-02-01 to 2027-02-01\n"
            "Covered Perils: collision, theft\nExclusions: dui\n"
            "Deductible: $1,000.00\nCoverage Limit: $40,000.00",
        ),
        _chunk(
            "d3",
            claim_id,
            "invoice.json",
            DocumentType.INVOICE,
            "Vendor: MetroWest\nClaimant Name: M. Thornton\nInvoice Total: $14,200.00",
        ),
    ]

    facts = extract_deterministic_facts(claim_id, chunks)
    links = resolve_entities(claim_id, facts)
    coverage = evaluate_policy_coverage(claim_id, facts)
    inconsistencies, missing = detect_inconsistencies_and_missing_evidence(
        claim_id=claim_id,
        documents=docs,
        chunks=chunks,
        facts=facts,
        entity_links=links,
        coverage=coverage,
    )
    risk = assess_claim_risk(
        claim_id=claim_id,
        coverage=coverage,
        inconsistencies=inconsistencies,
        missing_evidence=missing,
    )

    assert coverage.verdict == CoverageVerdict.EXCLUDED
    inc_codes = {f.code for f in inconsistencies}
    assert {
        "AMOUNT_MISMATCH",
        "INCIDENT_OUTSIDE_POLICY_PERIOD",
        "CLAIMANT_NAME_MISMATCH",
    }.issubset(inc_codes)
    assert any(m.code == "MISSING_POLICE_REPORT" for m in missing)
    # 30 (outside period) + 25 (amount) + 15 (identity) + 15 (missing police) + 10 (excluded) = 95
    assert risk.total_score == 95
    assert risk.severity == Severity.HIGH
    assert risk.advisory_only is True


def test_partial_coverage_caps_payable_at_limit() -> None:
    claim_id = "CLM-PARTIAL"
    chunks = [
        _chunk(
            "d1",
            claim_id,
            "claim.txt",
            DocumentType.CLAIM_FORM,
            "Policy Number: POL-2026-9000\nIncident Date: 2026-06-10\n"
            "Incident Type: Fire\nClaimed Amount: $30,000.00",
        ),
        _chunk(
            "d2",
            claim_id,
            "policy.txt",
            DocumentType.POLICY_SCHEDULE,
            "Policy Number: POL-2026-9000\nEffective Period: 2026-01-01 to 2026-12-31\n"
            "Covered Perils: fire\nDeductible: $1,000.00\nCoverage Limit: $20,000.00",
        ),
    ]
    facts = extract_deterministic_facts(claim_id, chunks)
    coverage = evaluate_policy_coverage(claim_id, facts)
    assert coverage.verdict == CoverageVerdict.PARTIAL
    assert coverage.estimated_payable == Decimal("20000.00")
