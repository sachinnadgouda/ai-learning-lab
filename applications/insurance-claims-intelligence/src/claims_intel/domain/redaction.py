"""Sensitive PII and secret redaction for logs, diagnostics, and audit payloads."""

from __future__ import annotations

import re
from typing import Any

_SSN_RE = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]\d{3}[-.\s]\d{4}\b")
_POLICY_OR_ACCOUNT_RE = re.compile(
    r"\b((?:POL|ACCT|ACC)[-_][A-Z0-9]{2,8}[-_])([A-Z0-9-]{2,16})\b",
    re.IGNORECASE,
)
_CARD_RE = re.compile(r"\b(?:\d{4}[-\s]?){3}\d{4}\b")
_BEARER_RE = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._\-]{8,}")
_SECRET_KV_RE = re.compile(
    r"(?i)\b(api[_-]?key|x-api-key|authorization|token|password|secret)\s*[:=]\s*([^\s,;\"']+)"
)

_SENSITIVE_KEY_NAMES = frozenset(
    {
        "api_key",
        "x-api-key",
        "authorization",
        "password",
        "secret",
        "token",
        "ssn",
        "social_security_number",
        "bank_account",
    }
)


def redact_text(value: str) -> str:
    """Mask sensitive claim PII and credentials in free-form strings."""
    masked = _SSN_RE.sub("[REDACTED-SSN]", value)
    masked = _EMAIL_RE.sub("[REDACTED-EMAIL]", masked)
    masked = _PHONE_RE.sub("[REDACTED-PHONE]", masked)
    masked = _CARD_RE.sub("[REDACTED-CARD]", masked)
    masked = _POLICY_OR_ACCOUNT_RE.sub(
        lambda m: f"{m.group(1)}***{m.group(2)[-2:] if len(m.group(2)) >= 2 else '**'}",
        masked,
    )
    masked = _BEARER_RE.sub(r"\1[REDACTED-TOKEN]", masked)
    masked = _SECRET_KV_RE.sub(r"\1=[REDACTED]", masked)
    return masked


def redact_mapping(payload: dict[str, Any]) -> dict[str, Any]:
    """Recursively redact sensitive keys and PII strings inside a dictionary."""
    redacted: dict[str, Any] = {}
    for key, val in payload.items():
        if key.lower() in _SENSITIVE_KEY_NAMES:
            redacted[key] = "[REDACTED]"
        else:
            redacted[key] = _redact_value(val)
    return redacted


def _redact_value(value: Any) -> Any:
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return redact_mapping(value)
    if isinstance(value, list):
        return [_redact_value(item) for item in value]
    return value
