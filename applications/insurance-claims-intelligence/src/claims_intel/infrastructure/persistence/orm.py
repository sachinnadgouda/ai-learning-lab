"""SQLAlchemy 2.x ORM schema for claims, documents, chunks, analysis, reviews, and audits."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """Declarative base for all ORM models."""


class ClaimORM(Base):
    __tablename__ = "claims"

    claim_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    external_reference: Mapped[str | None] = mapped_column(String(128), nullable=True)
    policy_number: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    claimant_name: Mapped[str | None] = mapped_column(String(160), nullable=True)
    incident_type: Mapped[str | None] = mapped_column(String(80), nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class DocumentORM(Base):
    __tablename__ = "claim_documents"
    __table_args__ = (
        UniqueConstraint("claim_id", "content_sha256", name="uq_claim_doc_sha256"),
    )

    document_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), nullable=False, index=True
    )
    filename: Mapped[str] = mapped_column(String(200), nullable=False)
    content_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    mime_type: Mapped[str] = mapped_column(String(100), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    document_type: Mapped[str] = mapped_column(String(40), nullable=False, index=True)
    page_count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    ocr_status: Mapped[str] = mapped_column(String(32), nullable=False)
    ocr_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    injection_flagged: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    uploaded_by: Mapped[str] = mapped_column(String(128), nullable=False)
    uploaded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class EvidenceChunkORM(Base):
    __tablename__ = "evidence_chunks"
    __table_args__ = (
        Index("ix_chunks_claim_doctype", "claim_id", "document_type"),
    )

    chunk_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), nullable=False, index=True
    )
    document_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("claim_documents.document_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    filename: Mapped[str] = mapped_column(String(200), nullable=False)
    document_type: Mapped[str] = mapped_column(String(40), nullable=False)
    page_number: Mapped[int] = mapped_column(Integer, nullable=False)
    char_start: Mapped[int] = mapped_column(Integer, nullable=False)
    char_end: Mapped[int] = mapped_column(Integer, nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding_json: Mapped[str] = mapped_column(Text, nullable=False)


class ExtractedFactORM(Base):
    __tablename__ = "extracted_facts"

    fact_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), nullable=False, index=True
    )
    field_name: Mapped[str] = mapped_column(String(80), nullable=False)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    normalized_value: Mapped[str | None] = mapped_column(Text, nullable=True)
    classification: Mapped[str] = mapped_column(String(32), nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    source_method: Mapped[str] = mapped_column(String(64), nullable=False)
    evidence_refs_json: Mapped[str] = mapped_column(Text, nullable=False)


class EntityLinkORM(Base):
    __tablename__ = "entity_links"

    link_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), nullable=False, index=True
    )
    entity_type: Mapped[str] = mapped_column(String(64), nullable=False)
    canonical_value: Mapped[str] = mapped_column(Text, nullable=False)
    observed_values_json: Mapped[str] = mapped_column(Text, nullable=False)
    document_ids_json: Mapped[str] = mapped_column(Text, nullable=False)
    has_conflict: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    conflict_detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    evidence_refs_json: Mapped[str] = mapped_column(Text, nullable=False)


class ClaimAnalysisSnapshotORM(Base):
    """Stores latest policy coverage, inconsistencies, missing evidence, and risk assessment."""

    __tablename__ = "claim_analysis_snapshots"

    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), primary_key=True
    )
    coverage_json: Mapped[str] = mapped_column(Text, nullable=False)
    inconsistencies_json: Mapped[str] = mapped_column(Text, nullable=False)
    missing_evidence_json: Mapped[str] = mapped_column(Text, nullable=False)
    risk_assessment_json: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class ReviewDecisionORM(Base):
    __tablename__ = "review_decisions"

    decision_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    claim_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("claims.claim_id", ondelete="CASCADE"), nullable=False, index=True
    )
    decision: Mapped[str] = mapped_column(String(32), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(128), nullable=False)
    actor_type: Mapped[str] = mapped_column(String(32), nullable=False)
    actor_role: Mapped[str] = mapped_column(String(32), nullable=False)
    rationale: Mapped[str] = mapped_column(Text, nullable=False)
    supervisor_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AuditEventORM(Base):
    __tablename__ = "audit_events"

    event_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    claim_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    actor_id: Mapped[str] = mapped_column(String(128), nullable=False)
    actor_type: Mapped[str] = mapped_column(String(32), nullable=False)
    correlation_id: Mapped[str] = mapped_column(String(64), nullable=False, default="")
    summary: Mapped[str] = mapped_column(Text, nullable=False)
    metadata_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
