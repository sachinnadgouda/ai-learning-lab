"""SQLAlchemy 2.x implementations of ClaimRepository and AuditRepository ports."""

from __future__ import annotations

import json
from collections.abc import Sequence
from datetime import UTC, datetime

from pydantic import TypeAdapter
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from claims_intel.application.ports import ClaimAnalysisResult
from claims_intel.domain.errors import DuplicateDocumentError
from claims_intel.domain.models import (
    ActorType,
    AuditEvent,
    Claim,
    ClaimStatus,
    DocumentRecord,
    DocumentType,
    EntityLink,
    EvidenceChunk,
    EvidenceRef,
    ExtractedFact,
    FactClassification,
    InconsistencyFinding,
    MissingEvidenceItem,
    OcrStatus,
    PolicyCoverageResult,
    ReviewDecision,
    ReviewDecisionType,
    RiskAssessment,
    Role,
)
from claims_intel.domain.redaction import redact_mapping, redact_text
from claims_intel.domain.retrieval import IndexedChunk
from claims_intel.infrastructure.persistence.orm import (
    AuditEventORM,
    ClaimAnalysisSnapshotORM,
    ClaimORM,
    DocumentORM,
    EntityLinkORM,
    EvidenceChunkORM,
    ExtractedFactORM,
    ReviewDecisionORM,
)

_EVIDENCE_REF_LIST = TypeAdapter(list[EvidenceRef])
_INCONSISTENCY_LIST = TypeAdapter(list[InconsistencyFinding])
_MISSING_LIST = TypeAdapter(list[MissingEvidenceItem])


def _ensure_utc(dt: datetime) -> datetime:
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


class SqlAlchemyClaimRepository:
    """Relational persistence adapter for claims, documents, chunks, analysis, and decisions."""

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def save_claim(self, claim: Claim) -> Claim:
        with self._session_factory() as session:
            existing = session.get(ClaimORM, claim.claim_id)
            if existing is None:
                row = ClaimORM(
                    claim_id=claim.claim_id,
                    external_reference=claim.external_reference,
                    policy_number=claim.policy_number,
                    claimant_name=claim.claimant_name,
                    incident_type=claim.incident_type,
                    status=claim.status.value,
                    created_at=claim.created_at,
                    updated_at=claim.updated_at,
                )
                session.add(row)
            else:
                existing.external_reference = claim.external_reference
                existing.policy_number = claim.policy_number
                existing.claimant_name = claim.claimant_name
                existing.incident_type = claim.incident_type
                existing.status = claim.status.value
                existing.updated_at = claim.updated_at
            session.commit()
            return self._to_claim(session, claim.claim_id)

    def get_claim(self, claim_id: str) -> Claim | None:
        with self._session_factory() as session:
            row = session.get(ClaimORM, claim_id)
            if row is None:
                return None
            return self._hydrate_claim(session, row)

    def list_claims(self, *, limit: int = 50, offset: int = 0) -> list[Claim]:
        with self._session_factory() as session:
            stmt = (
                select(ClaimORM)
                .order_by(ClaimORM.created_at.desc())
                .limit(limit)
                .offset(offset)
            )
            rows = session.scalars(stmt).all()
            return [self._hydrate_claim(session, row) for row in rows]

    def save_document_and_chunks(
        self,
        document: DocumentRecord,
        indexed_chunks: Sequence[IndexedChunk],
    ) -> DocumentRecord:
        with self._session_factory() as session:
            doc_row = DocumentORM(
                document_id=document.document_id,
                claim_id=document.claim_id,
                filename=document.filename,
                content_sha256=document.content_sha256,
                mime_type=document.mime_type,
                size_bytes=document.size_bytes,
                document_type=document.document_type.value,
                page_count=document.page_count,
                ocr_status=document.ocr_status.value,
                ocr_error=document.ocr_error,
                injection_flagged=document.injection_flagged,
                uploaded_by=document.uploaded_by,
                uploaded_at=document.uploaded_at,
            )
            try:
                session.add(doc_row)
                session.flush()

                for ic in indexed_chunks:
                    chunk_row = EvidenceChunkORM(
                        chunk_id=ic.chunk.chunk_id,
                        claim_id=ic.chunk.claim_id,
                        document_id=ic.chunk.document_id,
                        filename=ic.chunk.filename,
                        document_type=ic.chunk.document_type.value,
                        page_number=ic.chunk.page_number,
                        char_start=ic.chunk.char_start,
                        char_end=ic.chunk.char_end,
                        text=ic.chunk.text,
                        embedding_json=json.dumps(ic.embedding),
                    )
                    session.add(chunk_row)

                session.commit()
                return document
            except IntegrityError:
                session.rollback()
                existing_row = session.scalar(
                    select(DocumentORM).where(
                        DocumentORM.claim_id == document.claim_id,
                        DocumentORM.content_sha256 == document.content_sha256,
                    )
                )
                if existing_row is not None:
                    raise DuplicateDocumentError(
                        document.claim_id,
                        document.content_sha256,
                        existing_document=self._to_document(existing_row),
                    ) from None
                raise
            except Exception:
                session.rollback()
                raise

    def get_document_by_hash(self, claim_id: str, content_sha256: str) -> DocumentRecord | None:
        with self._session_factory() as session:
            stmt = select(DocumentORM).where(
                DocumentORM.claim_id == claim_id,
                DocumentORM.content_sha256 == content_sha256,
            )
            row = session.scalar(stmt)
            return self._to_document(row) if row is not None else None

    def list_documents(self, claim_id: str) -> list[DocumentRecord]:
        with self._session_factory() as session:
            stmt = (
                select(DocumentORM)
                .where(DocumentORM.claim_id == claim_id)
                .order_by(DocumentORM.uploaded_at.asc())
            )
            return [self._to_document(row) for row in session.scalars(stmt).all()]

    def list_indexed_chunks(
        self,
        claim_id: str,
        *,
        document_types: set[DocumentType] | None = None,
    ) -> list[IndexedChunk]:
        with self._session_factory() as session:
            stmt = select(EvidenceChunkORM).where(EvidenceChunkORM.claim_id == claim_id)
            if document_types:
                stmt = stmt.where(
                    EvidenceChunkORM.document_type.in_([dt.value for dt in document_types])
                )
            stmt = stmt.order_by(
                EvidenceChunkORM.document_id.asc(),
                EvidenceChunkORM.page_number.asc(),
                EvidenceChunkORM.char_start.asc(),
            )
            rows = session.scalars(stmt).all()
            return [
                IndexedChunk(
                    chunk=EvidenceChunk(
                        chunk_id=row.chunk_id,
                        claim_id=row.claim_id,
                        document_id=row.document_id,
                        filename=row.filename,
                        document_type=DocumentType(row.document_type),
                        page_number=row.page_number,
                        char_start=row.char_start,
                        char_end=row.char_end,
                        text=row.text,
                    ),
                    embedding=json.loads(row.embedding_json),
                )
                for row in rows
            ]

    def save_analysis(
        self,
        *,
        claim_id: str,
        facts: Sequence[ExtractedFact],
        entity_links: Sequence[EntityLink],
        coverage: PolicyCoverageResult,
        inconsistencies: Sequence[InconsistencyFinding],
        missing_evidence: Sequence[MissingEvidenceItem],
        risk_assessment: RiskAssessment,
    ) -> None:
        with self._session_factory() as session:
            session.execute(delete(ExtractedFactORM).where(ExtractedFactORM.claim_id == claim_id))
            session.execute(delete(EntityLinkORM).where(EntityLinkORM.claim_id == claim_id))

            for fact in facts:
                session.add(
                    ExtractedFactORM(
                        fact_id=fact.fact_id,
                        claim_id=claim_id,
                        field_name=fact.field_name,
                        value=fact.value,
                        normalized_value=fact.normalized_value,
                        classification=fact.classification.value,
                        confidence=fact.confidence,
                        source_method=fact.source_method,
                        evidence_refs_json=_EVIDENCE_REF_LIST.dump_json(
                            fact.evidence_refs
                        ).decode("utf-8"),
                    )
                )

            for link in entity_links:
                session.add(
                    EntityLinkORM(
                        link_id=link.link_id,
                        claim_id=claim_id,
                        entity_type=link.entity_type,
                        canonical_value=link.canonical_value,
                        observed_values_json=json.dumps(link.observed_values),
                        document_ids_json=json.dumps(link.document_ids),
                        has_conflict=link.has_conflict,
                        conflict_detail=link.conflict_detail,
                        evidence_refs_json=_EVIDENCE_REF_LIST.dump_json(
                            link.evidence_refs
                        ).decode("utf-8"),
                    )
                )

            snapshot = ClaimAnalysisSnapshotORM(
                claim_id=claim_id,
                coverage_json=coverage.model_dump_json(),
                inconsistencies_json=_INCONSISTENCY_LIST.dump_json(list(inconsistencies)).decode(
                    "utf-8"
                ),
                missing_evidence_json=_MISSING_LIST.dump_json(list(missing_evidence)).decode(
                    "utf-8"
                ),
                risk_assessment_json=risk_assessment.model_dump_json(),
                updated_at=datetime.now(UTC),
            )
            session.merge(snapshot)
            session.commit()

    def get_analysis(self, claim_id: str) -> ClaimAnalysisResult | None:
        with self._session_factory() as session:
            claim_row = session.get(ClaimORM, claim_id)
            snapshot = session.get(ClaimAnalysisSnapshotORM, claim_id)
            if claim_row is None or snapshot is None:
                return None

            claim = self._hydrate_claim(session, claim_row)
            fact_rows = session.scalars(
                select(ExtractedFactORM).where(ExtractedFactORM.claim_id == claim_id)
            ).all()
            link_rows = session.scalars(
                select(EntityLinkORM).where(EntityLinkORM.claim_id == claim_id)
            ).all()

            facts = [
                ExtractedFact(
                    fact_id=r.fact_id,
                    claim_id=r.claim_id,
                    field_name=r.field_name,
                    value=r.value,
                    normalized_value=r.normalized_value,
                    classification=FactClassification(r.classification),
                    confidence=r.confidence,
                    source_method=r.source_method,
                    evidence_refs=_EVIDENCE_REF_LIST.validate_json(r.evidence_refs_json),
                )
                for r in fact_rows
            ]
            entity_links = [
                EntityLink(
                    link_id=r.link_id,
                    claim_id=r.claim_id,
                    entity_type=r.entity_type,
                    canonical_value=r.canonical_value,
                    observed_values=json.loads(r.observed_values_json),
                    document_ids=json.loads(r.document_ids_json),
                    has_conflict=r.has_conflict,
                    conflict_detail=r.conflict_detail,
                    evidence_refs=_EVIDENCE_REF_LIST.validate_json(r.evidence_refs_json),
                )
                for r in link_rows
            ]

            return ClaimAnalysisResult(
                claim=claim,
                facts=facts,
                entity_links=entity_links,
                coverage=PolicyCoverageResult.model_validate_json(snapshot.coverage_json),
                inconsistencies=_INCONSISTENCY_LIST.validate_json(snapshot.inconsistencies_json),
                missing_evidence=_MISSING_LIST.validate_json(snapshot.missing_evidence_json),
                risk_assessment=RiskAssessment.model_validate_json(snapshot.risk_assessment_json),
            )

    def save_review_decision(self, claim: Claim, decision: ReviewDecision) -> ReviewDecision:
        with self._session_factory() as session:
            claim_row = session.get(ClaimORM, claim.claim_id)
            if claim_row is not None:
                claim_row.status = claim.status.value
                claim_row.updated_at = claim.updated_at

            session.add(
                ReviewDecisionORM(
                    decision_id=decision.decision_id,
                    claim_id=decision.claim_id,
                    decision=decision.decision.value,
                    actor_id=decision.actor_id,
                    actor_type=decision.actor_type.value,
                    actor_role=decision.actor_role.value,
                    rationale=decision.rationale,
                    supervisor_note=decision.supervisor_note,
                    decided_at=decision.decided_at,
                )
            )
            session.commit()
            return decision

    def _to_claim(self, session: Session, claim_id: str) -> Claim:
        row = session.get(ClaimORM, claim_id)
        if row is None:
            raise RuntimeError(f"Expected claim {claim_id} to exist after save.")
        return self._hydrate_claim(session, row)

    def _hydrate_claim(self, session: Session, row: ClaimORM) -> Claim:
        decision_rows = session.scalars(
            select(ReviewDecisionORM)
            .where(ReviewDecisionORM.claim_id == row.claim_id)
            .order_by(ReviewDecisionORM.decided_at.asc())
        ).all()
        decisions = [
            ReviewDecision(
                decision_id=d.decision_id,
                claim_id=d.claim_id,
                decision=ReviewDecisionType(d.decision),
                actor_id=d.actor_id,
                actor_type=ActorType(d.actor_type),
                actor_role=Role(d.actor_role),
                rationale=d.rationale,
                supervisor_note=d.supervisor_note,
                decided_at=_ensure_utc(d.decided_at),
            )
            for d in decision_rows
        ]
        return Claim(
            claim_id=row.claim_id,
            external_reference=row.external_reference,
            policy_number=row.policy_number,
            claimant_name=row.claimant_name,
            incident_type=row.incident_type,
            status=ClaimStatus(row.status),
            created_at=_ensure_utc(row.created_at),
            updated_at=_ensure_utc(row.updated_at),
            review_decisions=decisions,
        )

    def _to_document(self, row: DocumentORM) -> DocumentRecord:
        return DocumentRecord(
            document_id=row.document_id,
            claim_id=row.claim_id,
            filename=row.filename,
            content_sha256=row.content_sha256,
            mime_type=row.mime_type,
            size_bytes=row.size_bytes,
            document_type=DocumentType(row.document_type),
            page_count=row.page_count,
            ocr_status=OcrStatus(row.ocr_status),
            ocr_error=row.ocr_error,
            injection_flagged=row.injection_flagged,
            uploaded_by=row.uploaded_by,
            uploaded_at=_ensure_utc(row.uploaded_at),
        )


class SqlAlchemyAuditRepository:
    """Append-only SQLAlchemy audit trail with automatic PII masking."""

    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory

    def record(self, event: AuditEvent) -> AuditEvent:
        redacted_summary = redact_text(event.summary)
        redacted_meta = redact_mapping(event.metadata)
        clean_event = event.model_copy(
            update={"summary": redacted_summary, "metadata": redacted_meta}
        )
        with self._session_factory() as session:
            session.add(
                AuditEventORM(
                    event_id=clean_event.event_id,
                    claim_id=clean_event.claim_id,
                    event_type=clean_event.event_type,
                    actor_id=clean_event.actor_id,
                    actor_type=clean_event.actor_type.value,
                    correlation_id=clean_event.correlation_id,
                    summary=clean_event.summary,
                    metadata_json=json.dumps(clean_event.metadata, default=str),
                    created_at=clean_event.created_at,
                )
            )
            session.commit()
        return clean_event

    def list_for_claim(self, claim_id: str, *, limit: int = 100) -> list[AuditEvent]:
        with self._session_factory() as session:
            stmt = (
                select(AuditEventORM)
                .where(AuditEventORM.claim_id == claim_id)
                .order_by(AuditEventORM.created_at.asc())
                .limit(limit)
            )
            rows = session.scalars(stmt).all()
            return [
                AuditEvent(
                    event_id=r.event_id,
                    claim_id=r.claim_id,
                    event_type=r.event_type,
                    actor_id=r.actor_id,
                    actor_type=ActorType(r.actor_type),
                    correlation_id=r.correlation_id,
                    summary=r.summary,
                    metadata=json.loads(r.metadata_json),
                    created_at=_ensure_utc(r.created_at),
                )
                for r in rows
            ]
