"""Claim lifecycle and human-in-the-loop review governance use cases."""

from __future__ import annotations

from claims_intel.application.ports import AuditRepository, ClaimRepository
from claims_intel.domain.errors import (
    AutonomousDecisionProhibitedError,
    ClaimNotFoundError,
    UnauthorizedDecisionError,
)
from claims_intel.domain.models import (
    Actor,
    AuditEvent,
    Claim,
    ReviewDecision,
    ReviewDecisionType,
)
from claims_intel.domain.redaction import redact_text


class ReviewService:
    """Manages claim creation, submission to human review, and audited review decisions."""

    def __init__(
        self,
        *,
        claim_repo: ClaimRepository,
        audit_repo: AuditRepository,
    ) -> None:
        self._claim_repo = claim_repo
        self._audit_repo = audit_repo

    def create_claim(
        self,
        *,
        actor: Actor,
        claim_id: str | None = None,
        external_reference: str | None = None,
        policy_number: str | None = None,
        claimant_name: str | None = None,
        incident_type: str | None = None,
        correlation_id: str = "",
    ) -> Claim:
        kwargs = {
            "external_reference": external_reference,
            "policy_number": policy_number,
            "claimant_name": claimant_name,
            "incident_type": incident_type,
        }
        claim = Claim(claim_id=claim_id, **kwargs) if claim_id else Claim(**kwargs)
        saved = self._claim_repo.save_claim(claim)

        self._audit_repo.record(
            AuditEvent(
                claim_id=saved.claim_id,
                event_type="CLAIM_CREATED",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=redact_text(
                    f"Created claim {saved.claim_id} (policy={saved.policy_number or 'TBD'})."
                ),
                metadata={
                    "status": saved.status.value,
                    "external_reference": saved.external_reference,
                },
            )
        )
        return saved

    def get_claim(self, claim_id: str) -> Claim:
        claim = self._claim_repo.get_claim(claim_id)
        if claim is None:
            raise ClaimNotFoundError(claim_id)
        return claim

    def list_claims(self, *, limit: int = 50, offset: int = 0) -> list[Claim]:
        return self._claim_repo.list_claims(limit=limit, offset=offset)

    def submit_for_review(
        self,
        *,
        claim_id: str,
        actor: Actor,
        correlation_id: str = "",
    ) -> Claim:
        claim = self.get_claim(claim_id)
        previous_status = claim.status.value
        claim.submit_for_review()
        saved = self._claim_repo.save_claim(claim)

        self._audit_repo.record(
            AuditEvent(
                claim_id=claim_id,
                event_type="CLAIM_SUBMITTED_FOR_REVIEW",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=f"Claim {claim_id} moved from {previous_status} to IN_REVIEW.",
                metadata={"from_status": previous_status, "to_status": saved.status.value},
            )
        )
        return saved

    def record_decision(
        self,
        *,
        claim_id: str,
        actor: Actor,
        decision: ReviewDecisionType,
        rationale: str,
        supervisor_note: str | None = None,
        correlation_id: str = "",
    ) -> tuple[Claim, ReviewDecision]:
        claim = self.get_claim(claim_id)
        try:
            review_record = claim.record_review_decision(
                actor=actor,
                decision=decision,
                rationale=rationale,
                supervisor_note=supervisor_note,
            )
        except (AutonomousDecisionProhibitedError, UnauthorizedDecisionError) as exc:
            self._audit_repo.record(
                AuditEvent(
                    claim_id=claim_id,
                    event_type="REVIEW_DECISION_BLOCKED",
                    actor_id=actor.actor_id,
                    actor_type=actor.actor_type,
                    correlation_id=correlation_id,
                    summary=redact_text(
                        f"Blocked unauthorized {decision.value} attempt on claim {claim_id} "
                        f"by {actor.actor_id} ({actor.actor_type.value}/{actor.role.value})."
                    ),
                    metadata={
                        "decision": decision.value,
                        "actor_role": actor.role.value,
                        "error_code": exc.code,
                    },
                )
            )
            raise

        saved_decision = self._claim_repo.save_review_decision(claim, review_record)
        self._audit_repo.record(
            AuditEvent(
                claim_id=claim_id,
                event_type="REVIEW_DECISION_RECORDED",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=redact_text(
                    f"Human reviewer {actor.actor_id} ({actor.role.value}) recorded "
                    f"{decision.value} -> {claim.status.value} on claim {claim_id}."
                ),
                metadata={
                    "decision_id": saved_decision.decision_id,
                    "decision": decision.value,
                    "new_status": claim.status.value,
                    "actor_role": actor.role.value,
                    "has_supervisor_note": bool(supervisor_note),
                },
            )
        )
        return claim, saved_decision

    def get_audit_trail(self, claim_id: str, *, limit: int = 100) -> list[AuditEvent]:
        self.get_claim(claim_id)
        return self._audit_repo.list_for_claim(claim_id, limit=limit)
