"""Versioned /api/v1 HTTP endpoints and operational probes (/health, /ready, /metrics)."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, File, Form, Query, Request, UploadFile, status
from fastapi.responses import PlainTextResponse

from claims_intel import __version__
from claims_intel.application.ports import ClaimAnalysisResult, QaAnswerResult
from claims_intel.domain.models import Actor, Claim, DocumentType, PolicyCoverageResult, Role
from claims_intel.infrastructure.api.auth import (
    get_current_actor,
    require_human_reviewer,
    require_roles,
)
from claims_intel.infrastructure.api.dependencies import AppContainer
from claims_intel.infrastructure.api.schemas import (
    AuditTrailResponse,
    ClaimDetailResponse,
    CreateClaimRequest,
    DocumentUploadResponse,
    HealthResponse,
    QaRequest,
    ReadinessResponse,
    ReviewDecisionRequest,
    ReviewDecisionResponse,
    SearchRequest,
    SearchResponse,
)
from claims_intel.infrastructure.logging import correlation_id_ctx
from claims_intel.infrastructure.persistence.database import check_database_ready

ops_router = APIRouter(tags=["Operations"])
v1_router = APIRouter(prefix="/api/v1", tags=["Claims Intelligence v1"])


def _get_container(request: Request) -> AppContainer:
    container: AppContainer = request.app.state.container
    return container


# --- Operational Endpoints ---


@ops_router.get("/health", response_model=HealthResponse)
def health(container: AppContainer = Depends(_get_container)) -> HealthResponse:
    return HealthResponse(
        status="ok",
        service=container.settings.app_name,
        version=__version__,
        timestamp=datetime.now(UTC),
    )


@ops_router.get("/ready", response_model=ReadinessResponse)
def ready(container: AppContainer = Depends(_get_container)) -> ReadinessResponse:
    db_ok = check_database_ready(container.engine)
    return ReadinessResponse(
        status="ready" if db_ok else "degraded",
        database_ready=db_ok,
        ai_provider=container.llm.provider_name,
        embedding_provider=container.embedder.provider_name,
    )


@ops_router.get("/metrics", response_class=PlainTextResponse)
def metrics(container: AppContainer = Depends(_get_container)) -> PlainTextResponse:
    return PlainTextResponse(
        content=container.metrics.render_prometheus(),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )


# --- Claims Lifecycle Endpoints ---


@v1_router.post(
    "/claims",
    response_model=Claim,
    status_code=status.HTTP_201_CREATED,
)
def create_claim(
    payload: CreateClaimRequest,
    actor: Actor = Depends(require_roles(Role.ADJUSTER, Role.REVIEWER, Role.ADMIN)),
    container: AppContainer = Depends(_get_container),
) -> Claim:
    claim = container.review_service.create_claim(
        actor=actor,
        claim_id=payload.claim_id,
        external_reference=payload.external_reference,
        policy_number=payload.policy_number,
        claimant_name=payload.claimant_name,
        incident_type=payload.incident_type,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event("claim_created")
    return claim


@v1_router.get("/claims", response_model=list[Claim])
def list_claims(
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    _actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> list[Claim]:
    return container.review_service.list_claims(limit=limit, offset=offset)


@v1_router.get("/claims/{claim_id}", response_model=ClaimDetailResponse)
def get_claim_detail(
    claim_id: str,
    _actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> ClaimDetailResponse:
    claim = container.review_service.get_claim(claim_id)
    docs = container.claim_repo.list_documents(claim_id)
    analysis = container.claim_repo.get_analysis(claim_id)
    return ClaimDetailResponse(claim=claim, documents=docs, analysis=analysis)


# --- Document Upload & Multi-Format Ingestion ---


@v1_router.post(
    "/claims/{claim_id}/documents",
    response_model=DocumentUploadResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_document(
    claim_id: str,
    file: UploadFile = File(...),
    document_type: DocumentType | None = Form(default=None),
    actor: Actor = Depends(require_roles(Role.ADJUSTER, Role.REVIEWER, Role.ADMIN)),
    container: AppContainer = Depends(_get_container),
) -> DocumentUploadResponse:
    max_bytes = container.settings.max_upload_bytes
    # Read up to max_bytes + 1 to fail fast on oversized files without unbounded memory usage
    content = await file.read(max_bytes + 1)
    outcome = container.ingestion_service.ingest_document(
        claim_id=claim_id,
        raw_filename=file.filename or "",
        content=content,
        actor=actor,
        declared_mime_type=file.content_type,
        explicit_document_type=document_type,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event("document_ingested")
    if outcome.injection_warnings:
        container.metrics.increment_event("prompt_injection_flagged")
    return DocumentUploadResponse(
        document=outcome.document,
        chunk_count=outcome.chunk_count,
        deduplicated=outcome.deduplicated,
        injection_warnings=outcome.injection_warnings,
    )


# --- Extraction, Entity Resolution, Policy Coverage & Deterministic Risk Triage ---


@v1_router.post("/claims/{claim_id}/analyze", response_model=ClaimAnalysisResult)
def analyze_claim(
    claim_id: str,
    actor: Actor = Depends(
        require_roles(Role.ADJUSTER, Role.REVIEWER, Role.ADMIN, Role.AI_ASSISTANT)
    ),
    container: AppContainer = Depends(_get_container),
) -> ClaimAnalysisResult:
    result = container.extraction_service.analyze_claim(
        claim_id=claim_id,
        actor=actor,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event("claim_analyzed")
    return result


@v1_router.get("/claims/{claim_id}/coverage", response_model=PolicyCoverageResult)
def get_policy_coverage(
    claim_id: str,
    actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> PolicyCoverageResult:
    existing = container.claim_repo.get_analysis(claim_id)
    if existing is not None:
        return existing.coverage
    result = container.extraction_service.analyze_claim(
        claim_id=claim_id,
        actor=actor,
        correlation_id=correlation_id_ctx.get(""),
    )
    return result.coverage


# --- Hybrid Retrieval & Grounded Evidence Q&A ---


@v1_router.post("/claims/{claim_id}/search", response_model=SearchResponse)
def hybrid_search(
    claim_id: str,
    payload: SearchRequest,
    _actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> SearchResponse:
    doc_types = set(payload.document_types) if payload.document_types else None
    hits = container.qa_service.search_claim(
        claim_id=claim_id,
        query=payload.query,
        top_k=payload.top_k,
        document_types=doc_types,
    )
    return SearchResponse(claim_id=claim_id, query=payload.query, hits=hits)


@v1_router.post("/claims/{claim_id}/qa", response_model=QaAnswerResult)
def answer_claim_question(
    claim_id: str,
    payload: QaRequest,
    actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> QaAnswerResult:
    doc_types = set(payload.document_types) if payload.document_types else None
    result = container.qa_service.answer_question(
        claim_id=claim_id,
        question=payload.question,
        actor=actor,
        top_k=payload.top_k,
        document_types=doc_types,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event(
        "qa_abstained" if result.abstained else "qa_answered"
    )
    return result


# --- Human-in-the-Loop Review Governance ---


@v1_router.post("/claims/{claim_id}/submit-review", response_model=Claim)
def submit_for_review(
    claim_id: str,
    actor: Actor = Depends(require_roles(Role.ADJUSTER, Role.REVIEWER, Role.ADMIN)),
    container: AppContainer = Depends(_get_container),
) -> Claim:
    claim = container.review_service.submit_for_review(
        claim_id=claim_id,
        actor=actor,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event("claim_submitted_for_review")
    return claim


@v1_router.post("/claims/{claim_id}/review", response_model=ReviewDecisionResponse)
def record_human_review_decision(
    claim_id: str,
    payload: ReviewDecisionRequest,
    actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> ReviewDecisionResponse:
    # Enforce human-only reviewer gate even if claim_id does not exist, while auditing valid claims.
    existing = container.claim_repo.get_claim(claim_id)
    if existing is None:
        require_human_reviewer(actor)
    claim, decision = container.review_service.record_decision(
        claim_id=claim_id,
        actor=actor,
        decision=payload.decision,
        rationale=payload.rationale,
        supervisor_note=payload.supervisor_note,
        correlation_id=correlation_id_ctx.get(""),
    )
    container.metrics.increment_event(f"review_{decision.decision.value.lower()}")
    return ReviewDecisionResponse(claim=claim, review_decision=decision)


# --- Audit Trail ---


@v1_router.get("/claims/{claim_id}/audit", response_model=AuditTrailResponse)
def get_audit_trail(
    claim_id: str,
    limit: int = Query(default=100, ge=1, le=500),
    _actor: Actor = Depends(get_current_actor),
    container: AppContainer = Depends(_get_container),
) -> AuditTrailResponse:
    events = container.review_service.get_audit_trail(claim_id, limit=limit)
    return AuditTrailResponse(claim_id=claim_id, events=events)
