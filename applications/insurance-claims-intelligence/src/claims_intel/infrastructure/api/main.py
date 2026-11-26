"""FastAPI application factory with lifespan migrations, graceful shutdown, and narrow CORS."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from claims_intel import __version__
from claims_intel.application.ports import EmbeddingPort, LlmPort, OcrPort
from claims_intel.infrastructure.api.dependencies import build_container
from claims_intel.infrastructure.api.errors import register_exception_handlers
from claims_intel.infrastructure.api.middleware import CorrelationAndMetricsMiddleware
from claims_intel.infrastructure.api.routes import ops_router, v1_router
from claims_intel.infrastructure.config import Settings
from claims_intel.infrastructure.logging import configure_logging
from claims_intel.infrastructure.persistence.database import run_alembic_migrations

logger = logging.getLogger(__name__)


def create_app(
    *,
    settings: Settings | None = None,
    llm_override: LlmPort | None = None,
    embedder_override: EmbeddingPort | None = None,
    ocr_override: OcrPort | None = None,
) -> FastAPI:
    """Create and wire a fully configured FastAPI application instance."""
    resolved_settings = settings or Settings()
    configure_logging(resolved_settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if resolved_settings.auto_migrate:
            run_alembic_migrations(resolved_settings.database_url)
        container = build_container(
            resolved_settings,
            llm_override=llm_override,
            embedder_override=embedder_override,
            ocr_override=ocr_override,
        )
        app.state.container = container
        logger.info(
            "service_started",
            extra={
                "extra_fields": {
                    "app_env": resolved_settings.app_env,
                    "ai_provider": container.llm.provider_name,
                    "embedding_provider": container.embedder.provider_name,
                }
            },
        )
        try:
            yield
        finally:
            container.shutdown()
            logger.info("service_stopped")

    app = FastAPI(
        title="Insurance Claims Intelligence API",
        version=__version__,
        description=(
            "Evidence-backed insurance claims document intelligence, hybrid BM25+vector "
            "retrieval, deterministic risk triage, and human-in-the-loop review governance."
        ),
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved_settings.cors_origins_list,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["X-API-Key", "X-Correlation-ID", "Content-Type"],
    )
    app.add_middleware(CorrelationAndMetricsMiddleware)

    register_exception_handlers(app)
    app.include_router(ops_router)
    app.include_router(v1_router)
    return app


app = create_app()
