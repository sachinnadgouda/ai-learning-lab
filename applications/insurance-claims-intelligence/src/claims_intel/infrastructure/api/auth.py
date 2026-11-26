"""Server-side X-API-Key authentication mapped to explicit actors and roles."""

from __future__ import annotations

import hmac
from collections.abc import Callable

from fastapi import Depends, Header, Request

from claims_intel.domain.errors import (
    AutonomousDecisionProhibitedError,
    UnauthorizedDecisionError,
)
from claims_intel.domain.models import Actor, ActorType, Role
from claims_intel.infrastructure.config import Settings


class AuthenticationError(Exception):
    """Raised when X-API-Key is missing or invalid (HTTP 401)."""

    def __init__(self, detail: str = "Valid X-API-Key header is required.") -> None:
        super().__init__(detail)
        self.detail = detail


def resolve_actor_from_api_key(api_key: str | None, settings: Settings) -> Actor:
    """Resolve an API key using constant-time comparison into an authenticated Actor."""
    if not api_key:
        raise AuthenticationError("Missing X-API-Key header.")

    candidates: tuple[tuple[str, Actor], ...] = (
        (
            settings.admin_api_key,
            Actor(
                actor_id="human-admin",
                actor_type=ActorType.HUMAN,
                role=Role.ADMIN,
                display_name="Claims Governance Admin",
            ),
        ),
        (
            settings.reviewer_api_key,
            Actor(
                actor_id="human-reviewer",
                actor_type=ActorType.HUMAN,
                role=Role.REVIEWER,
                display_name="Senior Claims Reviewer",
            ),
        ),
        (
            settings.adjuster_api_key,
            Actor(
                actor_id="human-adjuster",
                actor_type=ActorType.HUMAN,
                role=Role.ADJUSTER,
                display_name="Claims Adjuster",
            ),
        ),
        (
            settings.ai_service_api_key,
            Actor(
                actor_id="claims-ai-assistant",
                actor_type=ActorType.AI_SYSTEM,
                role=Role.AI_ASSISTANT,
                display_name="Claims Intelligence AI Service",
            ),
        ),
    )

    for expected_key, actor in candidates:
        if expected_key and hmac.compare_digest(api_key, expected_key):
            return actor

    raise AuthenticationError("Invalid X-API-Key credential.")


def get_current_actor(
    request: Request,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
) -> Actor:
    settings: Settings = request.app.state.container.settings
    return resolve_actor_from_api_key(x_api_key, settings)


def require_roles(*allowed_roles: Role) -> Callable[..., Actor]:
    """Dependency factory enforcing server-side RBAC for a route."""

    def _checker(actor: Actor = Depends(get_current_actor)) -> Actor:
        if actor.role not in allowed_roles:
            raise UnauthorizedDecisionError(
                f"Role '{actor.role.value}' is not authorized for this operation. "
                f"Required role: {', '.join(r.value for r in allowed_roles)}."
            )
        return actor

    return _checker


def require_human_reviewer(actor: Actor = Depends(get_current_actor)) -> Actor:
    """Enforces human-only reviewer/admin authorization for claim decisions."""
    if actor.actor_type != ActorType.HUMAN:
        raise AutonomousDecisionProhibitedError(
            f"AI/non-human caller '{actor.actor_id}' is strictly prohibited from "
            "approving, rejecting, or authorizing payment on claims."
        )
    if actor.role not in {Role.REVIEWER, Role.ADMIN}:
        raise UnauthorizedDecisionError(
            f"Role '{actor.role.value}' cannot record claim review decisions. "
            "Required role: reviewer or admin."
        )
    return actor
