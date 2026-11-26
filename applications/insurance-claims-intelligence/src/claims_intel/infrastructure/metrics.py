"""Lightweight thread-safe Prometheus exposition metrics registry."""

from __future__ import annotations

import threading
from collections import defaultdict


class MetricsRegistry:
    """Collects HTTP, AI provider, and claims governance counters and latencies."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._http_requests: dict[tuple[str, str, int], int] = defaultdict(int)
        self._http_duration_sum: dict[tuple[str, str], float] = defaultdict(float)
        self._http_duration_count: dict[tuple[str, str], int] = defaultdict(int)
        self._ai_calls: dict[tuple[str, str, str], int] = defaultdict(int)
        self._ai_latency_ms_sum: dict[tuple[str, str], float] = defaultdict(float)
        self._ai_tokens: dict[tuple[str, str, str], int] = defaultdict(int)
        self._claims_events: dict[str, int] = defaultdict(int)

    def record_http_request(
        self, method: str, path: str, status_code: int, duration_seconds: float
    ) -> None:
        with self._lock:
            self._http_requests[(method, path, status_code)] += 1
            self._http_duration_sum[(method, path)] += duration_seconds
            self._http_duration_count[(method, path)] += 1

    def record_ai_call(
        self,
        *,
        provider: str,
        operation: str,
        status: str,
        latency_ms: float,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
    ) -> None:
        with self._lock:
            self._ai_calls[(provider, operation, status)] += 1
            self._ai_latency_ms_sum[(provider, operation)] += latency_ms
            if prompt_tokens:
                self._ai_tokens[(provider, operation, "prompt")] += prompt_tokens
            if completion_tokens:
                self._ai_tokens[(provider, operation, "completion")] += completion_tokens

    def increment_event(self, event_name: str) -> None:
        with self._lock:
            self._claims_events[event_name] += 1

    def render_prometheus(self) -> str:
        """Render all recorded metrics in Prometheus text exposition format 0.0.4."""
        lines: list[str] = [
            "# HELP claims_http_requests_total Total HTTP requests processed.",
            "# TYPE claims_http_requests_total counter",
        ]
        with self._lock:
            for (method, path, status), count in sorted(self._http_requests.items()):
                lines.append(
                    f'claims_http_requests_total{{method="{method}",path="{path}",'
                    f'status="{status}"}} {count}'
                )

            lines.extend(
                [
                    "# HELP claims_http_request_duration_seconds_sum Total HTTP request duration.",
                    "# TYPE claims_http_request_duration_seconds_sum counter",
                ]
            )
            for (method, path), total in sorted(self._http_duration_sum.items()):
                lines.append(
                    f'claims_http_request_duration_seconds_sum{{method="{method}",'
                    f'path="{path}"}} {total:.6f}'
                )

            lines.extend(
                [
                    "# HELP claims_http_request_duration_seconds_count Timed HTTP request count.",
                    "# TYPE claims_http_request_duration_seconds_count counter",
                ]
            )
            for (method, path), count in sorted(self._http_duration_count.items()):
                lines.append(
                    f'claims_http_request_duration_seconds_count{{method="{method}",'
                    f'path="{path}"}} {count}'
                )

            lines.extend(
                [
                    "# HELP claims_ai_calls_total Total AI provider invocations.",
                    "# TYPE claims_ai_calls_total counter",
                ]
            )
            for (provider, operation, ai_status), count in sorted(self._ai_calls.items()):
                lines.append(
                    f'claims_ai_calls_total{{provider="{provider}",operation="{operation}",'
                    f'status="{ai_status}"}} {count}'
                )

            lines.extend(
                [
                    "# HELP claims_ai_latency_ms_sum Cumulative AI provider latency in ms.",
                    "# TYPE claims_ai_latency_ms_sum counter",
                ]
            )
            for (provider, operation), total in sorted(self._ai_latency_ms_sum.items()):
                lines.append(
                    f'claims_ai_latency_ms_sum{{provider="{provider}",'
                    f'operation="{operation}"}} {total:.3f}'
                )

            lines.extend(
                [
                    "# HELP claims_ai_tokens_total Cumulative AI token usage.",
                    "# TYPE claims_ai_tokens_total counter",
                ]
            )
            for (provider, operation, kind), count in sorted(self._ai_tokens.items()):
                lines.append(
                    f'claims_ai_tokens_total{{provider="{provider}",operation="{operation}",'
                    f'kind="{kind}"}} {count}'
                )

            lines.extend(
                [
                    "# HELP claims_domain_events_total Claims workflow and governance events.",
                    "# TYPE claims_domain_events_total counter",
                ]
            )
            for name, count in sorted(self._claims_events.items()):
                lines.append(f'claims_domain_events_total{{event="{name}"}} {count}')

        return "\n".join(lines) + "\n"
