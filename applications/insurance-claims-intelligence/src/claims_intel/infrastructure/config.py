"""Startup-validated typed application settings via pydantic-settings."""

from __future__ import annotations

from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_name: str = "insurance-claims-intelligence"
    app_env: Literal["development", "test", "production"] = "development"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    host: str = "127.0.0.1"
    port: int = Field(default=8080, ge=1, le=65535)
    cors_allow_origins: str = "http://localhost:3000,http://127.0.0.1:3000"

    database_url: str = "sqlite:///./data/claims_intel.db"
    auto_migrate: bool = True

    max_upload_bytes: int = Field(default=10 * 1024 * 1024, ge=1024, le=100 * 1024 * 1024)
    chunk_size_chars: int = Field(default=700, ge=100, le=8000)
    chunk_overlap_chars: int = Field(default=120, ge=0, le=2000)
    tesseract_cmd: str = "tesseract"
    ocr_timeout_seconds: float = Field(default=15.0, gt=0.5, le=120.0)

    rrf_k: int = Field(default=60, ge=1, le=1000)
    rrf_bm25_weight: float = Field(default=1.0, ge=0.0, le=10.0)
    rrf_vector_weight: float = Field(default=1.0, ge=0.0, le=10.0)
    qa_min_evidence_score: float = Field(default=0.015, ge=0.0, le=1.0)
    qa_min_lexical_overlap: float = Field(default=0.18, ge=0.0, le=1.0)
    qa_top_k: int = Field(default=5, ge=1, le=25)

    ai_provider: Literal["local", "ollama", "openai_compatible"] = "local"
    embedding_provider: Literal["local", "ollama", "openai_compatible"] = "local"
    local_embedding_dim: int = Field(default=256, ge=32, le=2048)

    ollama_base_url: str = "http://127.0.0.1:11434"
    ollama_chat_model: str = "llama3.2:3b"
    ollama_embed_model: str = "nomic-embed-text:v1.5"

    openai_base_url: str = "https://api.openai.com/v1"
    openai_api_key: str = ""
    openai_chat_model: str = "gpt-4o-mini"
    openai_embed_model: str = "text-embedding-3-small"

    ai_connect_timeout_seconds: float = Field(default=5.0, gt=0.1, le=60.0)
    ai_read_timeout_seconds: float = Field(default=30.0, gt=0.5, le=300.0)
    ai_max_retries: int = Field(default=2, ge=0, le=6)
    ai_backoff_base_seconds: float = Field(default=0.5, ge=0.01, le=10.0)

    adjuster_api_key: str = "dev-adjuster-key-change-me"
    reviewer_api_key: str = "dev-reviewer-key-change-me"
    admin_api_key: str = "dev-admin-key-change-me"
    ai_service_api_key: str = "dev-ai-service-key-change-me"

    @field_validator("chunk_overlap_chars")
    @classmethod
    def _validate_overlap(cls, v: int, info: object) -> int:
        data = getattr(info, "data", {})
        size = data.get("chunk_size_chars", 700)
        if isinstance(size, int) and v >= size:
            raise ValueError("chunk_overlap_chars must be less than chunk_size_chars")
        return v

    @property
    def cors_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allow_origins.split(",") if origin.strip()]
