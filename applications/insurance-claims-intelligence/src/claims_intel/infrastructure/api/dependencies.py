"""Explicit composition root wiring domain, application services, and infrastructure adapters."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from claims_intel.application.extraction_service import ExtractionService
from claims_intel.application.ingestion_service import IngestionService
from claims_intel.application.ports import EmbeddingPort, LlmPort, OcrPort
from claims_intel.application.qa_service import QaService
from claims_intel.application.review_service import ReviewService
from claims_intel.domain.retrieval import HybridRetriever
from claims_intel.infrastructure.ai.providers import (
    build_embedding_provider,
    build_llm_provider,
)
from claims_intel.infrastructure.config import Settings
from claims_intel.infrastructure.metrics import MetricsRegistry
from claims_intel.infrastructure.parsers.document_parser import MultiFormatDocumentParser
from claims_intel.infrastructure.parsers.ocr import TesseractOcrAdapter
from claims_intel.infrastructure.persistence.database import (
    create_db_engine,
    create_session_factory,
)
from claims_intel.infrastructure.persistence.repository import (
    SqlAlchemyAuditRepository,
    SqlAlchemyClaimRepository,
)


@dataclass
class AppContainer:
    settings: Settings
    engine: Engine
    session_factory: sessionmaker[Session]
    metrics: MetricsRegistry
    claim_repo: SqlAlchemyClaimRepository
    audit_repo: SqlAlchemyAuditRepository
    embedder: EmbeddingPort
    llm: LlmPort
    ingestion_service: IngestionService
    extraction_service: ExtractionService
    qa_service: QaService
    review_service: ReviewService

    def shutdown(self) -> None:
        for provider in (self.embedder, self.llm):
            close_fn = getattr(provider, "close", None)
            if callable(close_fn):
                close_fn()
        self.engine.dispose()


def build_container(
    settings: Settings,
    *,
    llm_override: LlmPort | None = None,
    embedder_override: EmbeddingPort | None = None,
    ocr_override: OcrPort | None = None,
) -> AppContainer:
    """Instantiate all infrastructure adapters and wire hexagonal services."""
    metrics = MetricsRegistry()
    engine = create_db_engine(settings.database_url)
    session_factory = create_session_factory(engine)

    claim_repo = SqlAlchemyClaimRepository(session_factory)
    audit_repo = SqlAlchemyAuditRepository(session_factory)

    ocr_adapter: OcrPort = ocr_override or TesseractOcrAdapter(
        tesseract_cmd=settings.tesseract_cmd,
        timeout_seconds=settings.ocr_timeout_seconds,
    )
    parser = MultiFormatDocumentParser(ocr_adapter=ocr_adapter)

    embedder = embedder_override or build_embedding_provider(settings, metrics=metrics)
    llm = llm_override or build_llm_provider(settings, metrics=metrics)

    retriever = HybridRetriever(
        rrf_k=settings.rrf_k,
        bm25_weight=settings.rrf_bm25_weight,
        vector_weight=settings.rrf_vector_weight,
    )

    ingestion_service = IngestionService(
        claim_repo=claim_repo,
        audit_repo=audit_repo,
        parser=parser,
        embedder=embedder,
        max_upload_bytes=settings.max_upload_bytes,
        chunk_size_chars=settings.chunk_size_chars,
        chunk_overlap_chars=settings.chunk_overlap_chars,
    )
    extraction_service = ExtractionService(
        claim_repo=claim_repo,
        audit_repo=audit_repo,
        llm=llm,
    )
    qa_service = QaService(
        claim_repo=claim_repo,
        audit_repo=audit_repo,
        embedder=embedder,
        llm=llm,
        retriever=retriever,
        min_evidence_score=settings.qa_min_evidence_score,
        min_lexical_overlap=settings.qa_min_lexical_overlap,
        default_top_k=settings.qa_top_k,
    )
    review_service = ReviewService(
        claim_repo=claim_repo,
        audit_repo=audit_repo,
    )

    return AppContainer(
        settings=settings,
        engine=engine,
        session_factory=session_factory,
        metrics=metrics,
        claim_repo=claim_repo,
        audit_repo=audit_repo,
        embedder=embedder,
        llm=llm,
        ingestion_service=ingestion_service,
        extraction_service=extraction_service,
        qa_service=qa_service,
        review_service=review_service,
    )
