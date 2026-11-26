"""Shared pytest fixtures using project-local scratch directories (never /tmp)."""

from __future__ import annotations

import shutil
from collections.abc import Iterator
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from claims_intel.domain.errors import OcrUnavailableError
from claims_intel.infrastructure.api.main import create_app
from claims_intel.infrastructure.config import Settings

TEST_SCRATCH_ROOT = Path(__file__).resolve().parent.parent / ".pytest_scratch"


class AlwaysUnavailableOcr:
    """Deterministic OCR stub simulating an environment without Tesseract."""

    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        raise OcrUnavailableError(f"Simulated OCR unavailability for {filename}")


@pytest.fixture
def scratch_dir() -> Iterator[Path]:
    """Create and clean up a project-local scratch directory for each test."""
    TEST_SCRATCH_ROOT.mkdir(parents=True, exist_ok=True)
    run_dir = TEST_SCRATCH_ROOT / f"run-{uuid4().hex[:12]}"
    run_dir.mkdir(parents=True, exist_ok=True)
    try:
        yield run_dir
    finally:
        shutil.rmtree(run_dir, ignore_errors=True)
        if TEST_SCRATCH_ROOT.exists() and not any(TEST_SCRATCH_ROOT.iterdir()):
            TEST_SCRATCH_ROOT.rmdir()


@pytest.fixture
def test_settings(scratch_dir: Path) -> Settings:
    db_path = scratch_dir / "test_claims.db"
    return Settings(
        app_env="test",
        log_level="WARNING",
        database_url=f"sqlite:///{db_path}",
        auto_migrate=True,
        max_upload_bytes=64 * 1024,
        ai_provider="local",
        embedding_provider="local",
        adjuster_api_key="test-adjuster-key",
        reviewer_api_key="test-reviewer-key",
        admin_api_key="test-admin-key",
        ai_service_api_key="test-ai-service-key",
    )


@pytest.fixture
def client(test_settings: Settings) -> Iterator[TestClient]:
    app = create_app(settings=test_settings, ocr_override=AlwaysUnavailableOcr())
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def adjuster_headers() -> dict[str, str]:
    return {"X-API-Key": "test-adjuster-key"}


@pytest.fixture
def reviewer_headers() -> dict[str, str]:
    return {"X-API-Key": "test-reviewer-key"}


@pytest.fixture
def admin_headers() -> dict[str, str]:
    return {"X-API-Key": "test-admin-key"}


@pytest.fixture
def ai_service_headers() -> dict[str, str]:
    return {"X-API-Key": "test-ai-service-key"}
