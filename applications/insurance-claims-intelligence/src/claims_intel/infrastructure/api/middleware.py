"""Correlation ID propagation, HTTP metrics recording, and structured access logging."""

from __future__ import annotations

import logging
import time
from collections.abc import Awaitable, Callable
from uuid import uuid4

from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware

from claims_intel.infrastructure.logging import correlation_id_ctx

logger = logging.getLogger("claims_intel.http")


class CorrelationAndMetricsMiddleware(BaseHTTPMiddleware):
    """Attaches X-Correlation-ID to every request/response and records Prometheus metrics."""

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        corr_id = request.headers.get("X-Correlation-ID") or uuid4().hex
        token = correlation_id_ctx.set(corr_id)
        started = time.perf_counter()

        try:
            response = await call_next(request)
        finally:
            duration = time.perf_counter() - started
            correlation_id_ctx.reset(token)

        response.headers["X-Correlation-ID"] = corr_id

        container = getattr(request.app.state, "container", None)
        if container is not None:
            route = request.scope.get("route")
            path_label = getattr(route, "path", request.url.path)
            container.metrics.record_http_request(
                method=request.method,
                path=path_label,
                status_code=response.status_code,
                duration_seconds=duration,
            )

        logger.info(
            "http_request",
            extra={
                "extra_fields": {
                    "method": request.method,
                    "path": request.url.path,
                    "status_code": response.status_code,
                    "duration_ms": round(duration * 1000.0, 2),
                }
            },
        )
        return response
