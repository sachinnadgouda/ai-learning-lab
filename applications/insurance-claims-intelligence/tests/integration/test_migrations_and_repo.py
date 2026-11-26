"""Integration tests for Alembic migrations, SQLite WAL mode, and repository roundtrips."""

from __future__ import annotations

from pathlib import Path

from sqlalchemy import text

from claims_intel.domain.models import (
    Actor,
    ActorType,
    AuditEvent,
    Claim,
    DocumentRecord,
    DocumentType,
    EvidenceChunk,
    ReviewDecisionType,
    Role,
)
from claims_intel.domain.retrieval import IndexedChunk
from claims_intel.infrastructure.persistence.database import (
    create_db_engine,
    create_session_factory,
    run_alembic_migrations,
)
from claims_intel.infrastructure.persistence.repository import (
    SqlAlchemyAuditRepository,
    SqlAlchemyClaimRepository,
)


def test_alembic_migrations_wal_mode_and_repository_roundtrip(scratch_dir: Path) -> None:
    db_path = scratch_dir / "migrated.db"
    db_url = f"sqlite:///{db_path}"

    run_alembic_migrations(db_url)
    engine = create_db_engine(db_url)
    try:
        with engine.connect() as conn:
            journal_mode = conn.execute(text("PRAGMA journal_mode")).scalar_one()
            assert str(journal_mode).lower() == "wal"

        session_factory = create_session_factory(engine)
        claim_repo = SqlAlchemyClaimRepository(session_factory)
        audit_repo = SqlAlchemyAuditRepository(session_factory)

        claim = Claim(claim_id="CLM-REPO-1", policy_number="POL-AUTO-2026-1001")
        saved_claim = claim_repo.save_claim(claim)
        assert saved_claim.claim_id == "CLM-REPO-1"

        doc = DocumentRecord(
            document_id="doc-111",
            claim_id="CLM-REPO-1",
            filename="policy.txt",
            content_sha256="b" * 64,
            mime_type="text/plain",
            size_bytes=128,
            document_type=DocumentType.POLICY_SCHEDULE,
        )
        chunk = EvidenceChunk(
            chunk_id="doc-111:p1:c0",
            claim_id="CLM-REPO-1",
            document_id="doc-111",
            filename="policy.txt",
            document_type=DocumentType.POLICY_SCHEDULE,
            page_number=1,
            char_start=0,
            char_end=35,
            text="Policy POL-AUTO-2026-1001 active.",
        )
        claim_repo.save_document_and_chunks(doc, [IndexedChunk(chunk=chunk, embedding=[0.1, 0.9])])

        fetched_doc = claim_repo.get_document_by_hash("CLM-REPO-1", "b" * 64)
        assert fetched_doc is not None
        assert fetched_doc.document_id == "doc-111"

        indexed = claim_repo.list_indexed_chunks("CLM-REPO-1")
        assert len(indexed) == 1
        assert indexed[0].embedding == [0.1, 0.9]

        saved_claim.mark_extracted()
        saved_claim.submit_for_review()
        reviewer = Actor(actor_id="rev-1", actor_type=ActorType.HUMAN, role=Role.REVIEWER)
        decision = saved_claim.record_review_decision(
            actor=reviewer,
            decision=ReviewDecisionType.APPROVE,
            rationale="Verified against policy schedule.",
        )
        claim_repo.save_review_decision(saved_claim, decision)

        reloaded = claim_repo.get_claim("CLM-REPO-1")
        assert reloaded is not None
        assert reloaded.status.value == "APPROVED"
        assert len(reloaded.review_decisions) == 1

        # Audit redaction verification
        audit_repo.record(
            AuditEvent(
                claim_id="CLM-REPO-1",
                event_type="TEST_PII",
                actor_id="rev-1",
                actor_type=ActorType.HUMAN,
                summary="Checked claimant SSN 123-45-6789 and email test@insure.io",
                metadata={"x-api-key": "secret-val", "note": "phone 415-555-0100"},
            )
        )
        events = audit_repo.list_for_claim("CLM-REPO-1")
        assert len(events) == 1
        assert "123-45-6789" not in events[0].summary
        assert "[REDACTED-SSN]" in events[0].summary
        assert events[0].metadata["x-api-key"] == "[REDACTED]"
        assert "[REDACTED-PHONE]" in events[0].metadata["note"]
    finally:
        engine.dispose()
