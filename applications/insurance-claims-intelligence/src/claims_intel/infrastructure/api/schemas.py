"""Pydantic v2 HTTP request/response DTOs for /api/v1 endpoints."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from claims_intel.application.ports import ClaimAnalysisResult, QaAnswerResult
from claims_intel.domain.models import (
    AuditEvent,
    Claim,
    DocumentRecord,
    DocumentType,
    PolicyCoverageResult,
    ReviewDecision,
    ReviewDecisionType,
)
from claims_intel.domain.retrieval import RetrievalHit


class CreateClaimRequest(BaseModel):
    claim_id: str | None = Field(default=None, min_length=3, max_length=64)
    external_reference: str | None = Field(default=None, max_length=128)
    policy_number: str | None = Field(default=None, max_length=64)
    claimant_name: str | None = Field(default=None, max_length=160)
    incident_type: str | None = Field(default=None, max_length=80)


class DocumentUploadResponse(BaseModel):
    document: DocumentRecord
    chunk_count: int
    deduplicated: bool
    injection_warnings: list[str] = Field(default_factory=list)


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    top_k: int = Field(default=5, ge=1, le=20)
    document_types: list[DocumentType] | None = None


class SearchResponse(BaseModel):
    claim_id: str
    query: str
    hits: list[RetrievalHit]


class QaRequest(BaseModel):
    question: str = Field(min_length=3, max_length=600)
    top_k: int | None = Field(default=None, ge=1, le=15)
    document_types: list[DocumentType] | None = None


class ReviewDecisionRequest(BaseModel):
    decision: ReviewDecisionType
    rationale: str = Field(min_length=3, max_length=2000)
    supervisor_note: str | None = Field(default=None, max_length=2000)


class ReviewDecisionResponse(BaseModel):
    claim: Claim
    review_decision: ReviewDecision


class ClaimDetailResponse(BaseModel):
    claim: Claim
    documents: list[DocumentRecord]
    analysis: ClaimAnalysisResult | None = None


class AuditTrailResponse(BaseModel):
    claim_id: str
    events: list[AuditEvent]


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    timestamp: datetime


class ReadinessResponse(BaseModel):
    status: str
    database_ready: bool
    ai_provider: str
    embedding_provider: str


__all__ = [
    "AuditTrailResponse",
    "ClaimAnalysisResult",
    "ClaimDetailResponse",
    "CreateClaimRequest",
    "DocumentUploadResponse",
    "HealthResponse",
    "PolicyCoverageResult",
    "QaAnswerResult",
    "QaRequest",
    "ReadinessResponse",
    "ReviewDecisionRequest",
    "ReviewDecisionResponse",
    "SearchRequest",
    "SearchResponse",
]
