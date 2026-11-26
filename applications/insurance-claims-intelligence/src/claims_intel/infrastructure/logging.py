"""Structured JSON logging with correlation ID propagation and automatic PII redaction."""

from __future__ import annotations

import json
import logging
import sys
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any

from claims_intel.domain.redaction import redact_mapping, redact_text

correlation_id_ctx: ContextVar[str] = ContextVar("correlation_id", default="")


class JsonRedactingFormatter(logging.Formatter):
    """Formats log records as single-line JSON with PII/secret redaction."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "correlation_id": correlation_id_ctx.get(""),
            "message": redact_text(record.getMessage()),
        }
        extra_fields = getattr(record, "extra_fields", None)
        if isinstance(extra_fields, dict):
            payload["context"] = redact_mapping(extra_fields)
        if record.exc_info and record.exc_info[1] is not None:
            payload["exception_type"] = type(record.exc_info[1]).__name__
            payload["exception_message"] = redact_text(str(record.exc_info[1]))
        return json.dumps(payload, default=str)


def configure_logging(level: str = "INFO") -> None:
    """Configure root and application loggers to emit redacted structured JSON."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonRedactingFormatter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(getattr(logging, level.upper(), logging.INFO))
