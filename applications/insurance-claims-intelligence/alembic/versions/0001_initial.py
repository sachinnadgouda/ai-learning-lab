"""Initial schema for Insurance Claims Intelligence.

Revision ID: 0001_initial
Revises:
Create Date: 2026-11-26 00:00:00
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "claims",
        sa.Column("claim_id", sa.String(length=64), primary_key=True),
        sa.Column("external_reference", sa.String(length=128), nullable=True),
        sa.Column("policy_number", sa.String(length=64), nullable=True),
        sa.Column("claimant_name", sa.String(length=160), nullable=True),
        sa.Column("incident_type", sa.String(length=80), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_claims_policy_number", "claims", ["policy_number"])
    op.create_index("ix_claims_status", "claims", ["status"])

    op.create_table(
        "claim_documents",
        sa.Column("document_id", sa.String(length=64), primary_key=True),
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("filename", sa.String(length=200), nullable=False),
        sa.Column("content_sha256", sa.String(length=64), nullable=False),
        sa.Column("mime_type", sa.String(length=100), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("document_type", sa.String(length=40), nullable=False),
        sa.Column("page_count", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("ocr_status", sa.String(length=32), nullable=False),
        sa.Column("ocr_error", sa.Text(), nullable=True),
        sa.Column("injection_flagged", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("uploaded_by", sa.String(length=128), nullable=False),
        sa.Column("uploaded_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("claim_id", "content_sha256", name="uq_claim_doc_sha256"),
    )
    op.create_index("ix_claim_documents_claim_id", "claim_documents", ["claim_id"])
    op.create_index("ix_claim_documents_document_type", "claim_documents", ["document_type"])

    op.create_table(
        "evidence_chunks",
        sa.Column("chunk_id", sa.String(length=128), primary_key=True),
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "document_id",
            sa.String(length=64),
            sa.ForeignKey("claim_documents.document_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("filename", sa.String(length=200), nullable=False),
        sa.Column("document_type", sa.String(length=40), nullable=False),
        sa.Column("page_number", sa.Integer(), nullable=False),
        sa.Column("char_start", sa.Integer(), nullable=False),
        sa.Column("char_end", sa.Integer(), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("embedding_json", sa.Text(), nullable=False),
    )
    op.create_index("ix_evidence_chunks_claim_id", "evidence_chunks", ["claim_id"])
    op.create_index("ix_evidence_chunks_document_id", "evidence_chunks", ["document_id"])
    op.create_index("ix_chunks_claim_doctype", "evidence_chunks", ["claim_id", "document_type"])

    op.create_table(
        "extracted_facts",
        sa.Column("fact_id", sa.String(length=64), primary_key=True),
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("field_name", sa.String(length=80), nullable=False),
        sa.Column("value", sa.Text(), nullable=False),
        sa.Column("normalized_value", sa.Text(), nullable=True),
        sa.Column("classification", sa.String(length=32), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("source_method", sa.String(length=64), nullable=False),
        sa.Column("evidence_refs_json", sa.Text(), nullable=False),
    )
    op.create_index("ix_extracted_facts_claim_id", "extracted_facts", ["claim_id"])

    op.create_table(
        "entity_links",
        sa.Column("link_id", sa.String(length=64), primary_key=True),
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("entity_type", sa.String(length=64), nullable=False),
        sa.Column("canonical_value", sa.Text(), nullable=False),
        sa.Column("observed_values_json", sa.Text(), nullable=False),
        sa.Column("document_ids_json", sa.Text(), nullable=False),
        sa.Column("has_conflict", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("conflict_detail", sa.Text(), nullable=True),
        sa.Column("evidence_refs_json", sa.Text(), nullable=False),
    )
    op.create_index("ix_entity_links_claim_id", "entity_links", ["claim_id"])

    op.create_table(
        "claim_analysis_snapshots",
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("coverage_json", sa.Text(), nullable=False),
        sa.Column("inconsistencies_json", sa.Text(), nullable=False),
        sa.Column("missing_evidence_json", sa.Text(), nullable=False),
        sa.Column("risk_assessment_json", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.create_table(
        "review_decisions",
        sa.Column("decision_id", sa.String(length=64), primary_key=True),
        sa.Column(
            "claim_id",
            sa.String(length=64),
            sa.ForeignKey("claims.claim_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("decision", sa.String(length=32), nullable=False),
        sa.Column("actor_id", sa.String(length=128), nullable=False),
        sa.Column("actor_type", sa.String(length=32), nullable=False),
        sa.Column("actor_role", sa.String(length=32), nullable=False),
        sa.Column("rationale", sa.Text(), nullable=False),
        sa.Column("supervisor_note", sa.Text(), nullable=True),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_review_decisions_claim_id", "review_decisions", ["claim_id"])

    op.create_table(
        "audit_events",
        sa.Column("event_id", sa.String(length=64), primary_key=True),
        sa.Column("claim_id", sa.String(length=64), nullable=True),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("actor_id", sa.String(length=128), nullable=False),
        sa.Column("actor_type", sa.String(length=32), nullable=False),
        sa.Column("correlation_id", sa.String(length=64), nullable=False, server_default=""),
        sa.Column("summary", sa.Text(), nullable=False),
        sa.Column("metadata_json", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_audit_events_claim_id", "audit_events", ["claim_id"])
    op.create_index("ix_audit_events_event_type", "audit_events", ["event_type"])
    op.create_index("ix_audit_events_created_at", "audit_events", ["created_at"])


def downgrade() -> None:
    op.drop_table("audit_events")
    op.drop_table("review_decisions")
    op.drop_table("claim_analysis_snapshots")
    op.drop_table("entity_links")
    op.drop_table("extracted_facts")
    op.drop_table("evidence_chunks")
    op.drop_table("claim_documents")
    op.drop_table("claims")
