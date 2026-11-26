"""RFC 9457 Problem Details exception handlers with correlation IDs and zero leakage."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from claims_intel.domain.errors import (
    AutonomousDecisionProhibitedError,
    ClaimNotFoundError,
    ClaimsIntelError,
    InvalidDocumentError,
    InvalidFilenameError,
    InvalidStateTransitionError,
    PayloadTooLargeError,
    UnauthorizedDecisionError,
    UnsupportedDocumentFormatError,
    UpstreamAiError,
)
from claims_intel.domain.redaction import redact_text
from claims_intel.infrastructure.api.auth import AuthenticationError
from claims_intel.infrastructure.logging import correlation_id_ctx

logger = logging.getLogger(__name__)

PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"


def problem_response(
    *,
    request: Request,
    status_code: int,
    title: str,
    detail: str,
    error_code: str,
    extra: dict[str, Any] | None = None,
) -> JSONResponse:
    corr_id = correlation_id_ctx.get("") or request.headers.get("X-Correlation-ID", "")
    body: dict[str, Any] = {
        "type": f"https://claims-intel.local/problems/{error_code.lower().replace('_', '-')}",
        "title": title,
        "status": status_code,
        "detail": redact_text(detail),
        "instance": str(request.url.path),
        "error_code": error_code,
        "correlation_id": corr_id,
    }
    if extra:
        body.update(extra)
    return JSONResponse(
        status_code=status_code,
        content=body,
        media_type=PROBLEM_JSON_MEDIA_TYPE,
        headers={"X-Correlation-ID": corr_id} if corr_id else None,
    )


def register_exception_handlers(app: FastAPI) -> None:
    """Attach RFC 9457 Problem Details handlers for domain, auth, validation, and runtime errors."""

    @app.exception_handler(AuthenticationError)
    async def _handle_auth_error(request: Request, exc: AuthenticationError) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_401_UNAUTHORIZED,
            title="Authentication Required",
            detail=exc.detail,
            error_code="UNAUTHORIZED",
        )

    @app.exception_handler(AutonomousDecisionProhibitedError)
    async def _handle_autonomous_prohibited(
        request: Request, exc: AutonomousDecisionProhibitedError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_403_FORBIDDEN,
            title="Autonomous AI Claim Decision Prohibited",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(UnauthorizedDecisionError)
    async def _handle_unauthorized(
        request: Request, exc: UnauthorizedDecisionError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_403_FORBIDDEN,
            title="Forbidden",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(ClaimNotFoundError)
    async def _handle_not_found(request: Request, exc: ClaimNotFoundError) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_404_NOT_FOUND,
            title="Resource Not Found",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(InvalidStateTransitionError)
    async def _handle_invalid_transition(
        request: Request, exc: InvalidStateTransitionError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_409_CONFLICT,
            title="Invalid Claim State Transition",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(PayloadTooLargeError)
    async def _handle_payload_too_large(
        request: Request, exc: PayloadTooLargeError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            title="Payload Too Large",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(UnsupportedDocumentFormatError)
    async def _handle_unsupported_media(
        request: Request, exc: UnsupportedDocumentFormatError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            title="Unsupported Media Type",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(InvalidDocumentError)
    async def _handle_invalid_doc(request: Request, exc: InvalidDocumentError) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_400_BAD_REQUEST,
            title="Invalid Document",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(InvalidFilenameError)
    async def _handle_invalid_filename(
        request: Request, exc: InvalidFilenameError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_400_BAD_REQUEST,
            title="Invalid Filename",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(UpstreamAiError)
    async def _handle_upstream_ai(request: Request, exc: UpstreamAiError) -> JSONResponse:
        logger.warning(
            "Upstream AI failure mapped to 502",
            extra={"extra_fields": {"provider": exc.provider, "code": exc.code}},
        )
        return problem_response(
            request=request,
            status_code=status.HTTP_502_BAD_GATEWAY,
            title="Upstream AI Provider Unavailable",
            detail="The configured AI provider failed or returned an invalid structured response.",
            error_code=exc.code,
        )

    @app.exception_handler(ClaimsIntelError)
    async def _handle_domain_error(request: Request, exc: ClaimsIntelError) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_400_BAD_REQUEST,
            title="Domain Rule Violation",
            detail=exc.message,
            error_code=exc.code,
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            title="Validation Error",
            detail="Request validation failed. Check required fields and value constraints.",
            error_code="VALIDATION_ERROR",
            extra={"errors": exc.errors()},
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_exc(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        return problem_response(
            request=request,
            status_code=exc.status_code,
            title="HTTP Error",
            detail=str(exc.detail),
            error_code=f"HTTP_{exc.status_code}",
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("Unhandled server exception")
        return problem_response(
            request=request,
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            title="Internal Server Error",
            detail="An unexpected internal error occurred. Refer to correlation_id in server logs.",
            error_code="INTERNAL_SERVER_ERROR",
        )
