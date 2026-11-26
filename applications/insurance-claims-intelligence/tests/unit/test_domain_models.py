"""Unit tests for Claim aggregate state machine, governance invariant, and filename safety."""

from __future__ import annotations

import pytest

from claims_intel.domain.errors import (
    AutonomousDecisionProhibitedError,
    InvalidFilenameError,
    InvalidStateTransitionError,
    UnauthorizedDecisionError,
)
from claims_intel.domain.models import (
    Actor,
    ActorType,
    Claim,
    ClaimStatus,
    ReviewDecisionType,
    Role,
    sanitize_filename,
)


def _actor(actor_type: ActorType, role: Role, actor_id: str = "actor-1") -> Actor:
    return Actor(actor_id=actor_id, actor_type=actor_type, role=role)


def test_claim_valid_lifecycle_and_human_approval() -> None:
    claim = Claim(claim_id="CLM-TEST-1")
    assert claim.status == ClaimStatus.INTAKE

    claim.mark_extracted()
    assert claim.status == ClaimStatus.EXTRACTED

    claim.submit_for_review()
    assert claim.status == ClaimStatus.IN_REVIEW

    reviewer = _actor(ActorType.HUMAN, Role.REVIEWER, "rev-jane")
    decision = claim.record_review_decision(
        actor=reviewer,
        decision=ReviewDecisionType.APPROVE,
        rationale="Verified repair invoice matches policy coverage and deductible.",
        supervisor_note="Approved under standard auto threshold.",
    )
    assert claim.status == ClaimStatus.APPROVED
    assert decision.actor_id == "rev-jane"
    assert decision.supervisor_note == "Approved under standard auto threshold."
    assert len(claim.review_decisions) == 1

    # Terminal APPROVED state cannot transition further
    with pytest.raises(InvalidStateTransitionError):
        claim.transition_to(ClaimStatus.REJECTED)


def test_claim_needs_info_loop_back_to_review() -> None:
    claim = Claim(claim_id="CLM-TEST-2")
    claim.mark_extracted()
    claim.submit_for_review()

    reviewer = _actor(ActorType.HUMAN, Role.REVIEWER, "rev-alex")
    claim.record_review_decision(
        actor=reviewer,
        decision=ReviewDecisionType.REQUEST_INFO,
        rationale="Missing police report for high-value collision claim.",
    )
    assert claim.status == ClaimStatus.NEEDS_INFO

    claim.mark_extracted()
    claim.submit_for_review()
    admin = _actor(ActorType.HUMAN, Role.ADMIN, "adm-sam")
    claim.record_review_decision(
        actor=admin,
        decision=ReviewDecisionType.REJECT,
        rationale="Incident date precedes policy inception date.",
    )
    assert claim.status == ClaimStatus.REJECTED


@pytest.mark.parametrize(
    "decision",
    [
        ReviewDecisionType.APPROVE,
        ReviewDecisionType.REJECT,
        ReviewDecisionType.AUTHORIZE_PAYMENT,
        ReviewDecisionType.REQUEST_INFO,
    ],
)
@pytest.mark.parametrize("actor_type", [ActorType.AI_SYSTEM, ActorType.SERVICE])
def test_ai_or_service_actor_prohibited_from_deciding_claim(
    actor_type: ActorType,
    decision: ReviewDecisionType,
) -> None:
    claim = Claim(claim_id="CLM-GOV-1")
    claim.mark_extracted()
    claim.submit_for_review()

    non_human = _actor(actor_type, Role.ADMIN, "ai-agent-01")
    with pytest.raises(AutonomousDecisionProhibitedError):
        claim.record_review_decision(
            actor=non_human,
            decision=decision,
            rationale="Automated recommendation",
        )
    assert claim.status == ClaimStatus.IN_REVIEW
    assert claim.review_decisions == []


def test_human_adjuster_cannot_approve_or_reject() -> None:
    claim = Claim(claim_id="CLM-GOV-2")
    claim.mark_extracted()
    claim.submit_for_review()

    adjuster = _actor(ActorType.HUMAN, Role.ADJUSTER, "adj-bob")
    with pytest.raises(UnauthorizedDecisionError):
        claim.record_review_decision(
            actor=adjuster,
            decision=ReviewDecisionType.APPROVE,
            rationale="Looks good to me.",
        )
    assert claim.status == ClaimStatus.IN_REVIEW


def test_human_reviewer_cannot_decide_before_in_review() -> None:
    claim = Claim(claim_id="CLM-STATE-1")
    reviewer = _actor(ActorType.HUMAN, Role.REVIEWER, "rev-1")
    with pytest.raises(InvalidStateTransitionError):
        claim.record_review_decision(
            actor=reviewer,
            decision=ReviewDecisionType.APPROVE,
            rationale="Skipping extraction and review queue.",
        )


@pytest.mark.parametrize(
    "bad_name",
    [
        "",
        "../secret.pdf",
        "../../etc/passwd",
        "/etc/passwd",
        "folder/invoice.pdf",
        "folder\\invoice.pdf",
        "invoice.pdf\x00.txt",
        "no_extension",
    ],
)
def test_sanitize_filename_rejects_unsafe_inputs(bad_name: str) -> None:
    with pytest.raises(InvalidFilenameError):
        sanitize_filename(bad_name)


def test_sanitize_filename_accepts_and_cleans_valid_names() -> None:
    assert sanitize_filename("repair invoice (final).pdf") == "repair_invoice_final_.pdf"
    assert sanitize_filename("policy_schedule_2026.txt") == "policy_schedule_2026.txt"
