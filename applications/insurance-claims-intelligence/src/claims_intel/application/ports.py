"""Outbound hexagonal ports and application-level structured contracts."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field

from claims_intel.domain.chunking import ExtractedPage
from claims_intel.domain.models import (
    AuditEvent,
    Claim,
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
    RiskAssessment,
)
from claims_intel.domain.retrieval import IndexedChunk


class ParsedDocument(BaseModel):
    model_config = ConfigDict(frozen=True)

    mime_type: str
    pages: list[ExtractedPage] = Field(default_factory=list)
    ocr_status: OcrStatus = OcrStatus.NOT_REQUIRED
    ocr_error: str | None = None


class AiCallUsage(BaseModel):
    model_config = ConfigDict(frozen=True)

    provider: str
    model: str
    latency_ms: float = Field(ge=0.0)
    prompt_tokens: int = Field(ge=0, default=0)
    completion_tokens: int = Field(ge=0, default=0)


class QaCitation(BaseModel):
    model_config = ConfigDict(frozen=True)

    chunk_id: str = Field(min_length=1)
    quote: str = Field(min_length=1, max_length=400)


class QaLlmOutput(BaseModel):
    model_config = ConfigDict(frozen=True)

    answer: str = Field(min_length=1)
    abstained: bool = False
    abstain_reason: str | None = None
    confidence: float = Field(ge=0.0, le=1.0, default=0.0)
    citations: list[QaCitation] = Field(default_factory=list)
    extracted_facts_summary: list[str] = Field(default_factory=list)
    inferences_summary: list[str] = Field(default_factory=list)


class QaLlmResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    output: QaLlmOutput
    usage: AiCallUsage


class ExtractionLlmItem(BaseModel):
    model_config = ConfigDict(frozen=True)

    field_name: str = Field(min_length=1, max_length=80)
    value: str = Field(min_length=1, max_length=300)
    classification: FactClassification
    confidence: float = Field(ge=0.0, le=1.0)
    chunk_id: str = Field(min_length=1)
    quote: str = Field(min_length=1, max_length=300)


class ExtractionLlmOutput(BaseModel):
    model_config = ConfigDict(frozen=True)

    items: list[ExtractionLlmItem] = Field(default_factory=list)


class ExtractionLlmResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    output: ExtractionLlmOutput
    usage: AiCallUsage


class QaAnswerResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    claim_id: str
    question: str
    status: str  # "ANSWERED" | "UNANSWERABLE"
    answer: str
    abstained: bool
    abstain_reason: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    citations: list[EvidenceRef] = Field(default_factory=list)
    extracted_facts_summary: list[str] = Field(default_factory=list)
    inferences_summary: list[str] = Field(default_factory=list)
    prompt_injection_detected: bool = False
    injection_warnings: list[str] = Field(default_factory=list)
    usage: AiCallUsage | None = None


class ClaimAnalysisResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    claim: Claim
    facts: list[ExtractedFact]
    entity_links: list[EntityLink]
    coverage: PolicyCoverageResult
    inconsistencies: list[InconsistencyFinding]
    missing_evidence: list[MissingEvidenceItem]
    risk_assessment: RiskAssessment


class OcrPort(Protocol):
    """Pluggable OCR backend for extracting text from image bytes."""

    def extract_text(self, image_bytes: bytes, *, filename: str) -> str: ...


class DocumentParserPort(Protocol):
    """Validates file signatures and extracts page-level text from supported formats."""

    def parse(
        self,
        *,
        filename: str,
        content: bytes,
        declared_mime_type: str | None = None,
    ) -> ParsedDocument: ...


class EmbeddingPort(Protocol):
    """Generates dense vector embeddings for document chunks and queries."""

    @property
    def provider_name(self) -> str: ...

    @property
    def model_name(self) -> str: ...

    def embed_texts(self, texts: Sequence[str]) -> tuple[list[list[float]], AiCallUsage]: ...


class LlmPort(Protocol):
    """Generates schema-validated Q&A answers and structured extractions from fenced evidence."""

    @property
    def provider_name(self) -> str: ...

    @property
    def model_name(self) -> str: ...

    def answer_question(
        self,
        *,
        question: str,
        fenced_evidence: str,
        chunks: Sequence[EvidenceChunk],
    ) -> QaLlmResult: ...

    def extract_structured_facts(
        self,
        *,
        fenced_evidence: str,
        chunks: Sequence[EvidenceChunk],
    ) -> ExtractionLlmResult: ...


class ClaimRepository(Protocol):
    """Persistence port for claims, documents, chunks, analysis artifacts, and reviews."""

    def save_claim(self, claim: Claim) -> Claim: ...

    def get_claim(self, claim_id: str) -> Claim | None: ...

    def list_claims(self, *, limit: int = 50, offset: int = 0) -> list[Claim]: ...

    def save_document_and_chunks(
        self,
        document: DocumentRecord,
        indexed_chunks: Sequence[IndexedChunk],
    ) -> DocumentRecord: ...

    def get_document_by_hash(self, claim_id: str, content_sha256: str) -> DocumentRecord | None: ...

    def list_documents(self, claim_id: str) -> list[DocumentRecord]: ...

    def list_indexed_chunks(
        self,
        claim_id: str,
        *,
        document_types: set[DocumentType] | None = None,
    ) -> list[IndexedChunk]: ...

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
    ) -> None: ...

    def get_analysis(self, claim_id: str) -> ClaimAnalysisResult | None: ...

    def save_review_decision(self, claim: Claim, decision: ReviewDecision) -> ReviewDecision: ...


class AuditRepository(Protocol):
    """Append-only persistence port for operational and AI governance audit events."""

    def record(self, event: AuditEvent) -> AuditEvent: ...

    def list_for_claim(self, claim_id: str, *, limit: int = 100) -> list[AuditEvent]: ...
