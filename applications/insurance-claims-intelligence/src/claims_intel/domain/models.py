"""Pure domain models and invariants for Insurance Claims Intelligence.

Zero framework, database, or HTTP dependencies live here.
"""

from __future__ import annotations

import re
from datetime import UTC, date, datetime
from decimal import Decimal
from enum import StrEnum
from pathlib import PurePosixPath, PureWindowsPath
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator

from claims_intel.domain.errors import (
    AutonomousDecisionProhibitedError,
    InvalidFilenameError,
    InvalidStateTransitionError,
    UnauthorizedDecisionError,
)


def utc_now() -> datetime:
    """Return timezone-aware UTC timestamp."""
    return datetime.now(UTC)


class ClaimStatus(StrEnum):
    INTAKE = "INTAKE"
    EXTRACTED = "EXTRACTED"
    IN_REVIEW = "IN_REVIEW"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    NEEDS_INFO = "NEEDS_INFO"


ALLOWED_TRANSITIONS: dict[ClaimStatus, frozenset[ClaimStatus]] = {
    ClaimStatus.INTAKE: frozenset({ClaimStatus.EXTRACTED, ClaimStatus.IN_REVIEW}),
    ClaimStatus.EXTRACTED: frozenset({ClaimStatus.EXTRACTED, ClaimStatus.IN_REVIEW}),
    ClaimStatus.IN_REVIEW: frozenset(
        {ClaimStatus.APPROVED, ClaimStatus.REJECTED, ClaimStatus.NEEDS_INFO}
    ),
    ClaimStatus.NEEDS_INFO: frozenset({ClaimStatus.EXTRACTED, ClaimStatus.IN_REVIEW}),
    ClaimStatus.APPROVED: frozenset(),
    ClaimStatus.REJECTED: frozenset(),
}


class ActorType(StrEnum):
    HUMAN = "HUMAN"
    AI_SYSTEM = "AI_SYSTEM"
    SERVICE = "SERVICE"


class Role(StrEnum):
    ADJUSTER = "adjuster"
    REVIEWER = "reviewer"
    ADMIN = "admin"
    AI_ASSISTANT = "ai_assistant"


DECISION_AUTHORIZED_ROLES: frozenset[Role] = frozenset({Role.REVIEWER, Role.ADMIN})


class Actor(BaseModel):
    model_config = ConfigDict(frozen=True)

    actor_id: str = Field(min_length=1, max_length=128)
    actor_type: ActorType
    role: Role
    display_name: str = Field(default="", max_length=128)


class ReviewDecisionType(StrEnum):
    APPROVE = "APPROVE"
    REJECT = "REJECT"
    REQUEST_INFO = "REQUEST_INFO"
    AUTHORIZE_PAYMENT = "AUTHORIZE_PAYMENT"


class DocumentType(StrEnum):
    CLAIM_FORM = "CLAIM_FORM"
    POLICY_SCHEDULE = "POLICY_SCHEDULE"
    INVOICE = "INVOICE"
    REPAIR_ESTIMATE = "REPAIR_ESTIMATE"
    POLICE_REPORT = "POLICE_REPORT"
    MEDICAL_REPORT = "MEDICAL_REPORT"
    PHOTO_EVIDENCE = "PHOTO_EVIDENCE"
    CORRESPONDENCE = "CORRESPONDENCE"
    UNKNOWN = "UNKNOWN"


class OcrStatus(StrEnum):
    NOT_REQUIRED = "NOT_REQUIRED"
    COMPLETED = "COMPLETED"
    METADATA_ONLY = "METADATA_ONLY"
    FAILED = "FAILED"
    UNAVAILABLE = "UNAVAILABLE"


class FactClassification(StrEnum):
    EXTRACTED_FACT = "EXTRACTED_FACT"
    INFERRED = "INFERRED"
    UNCERTAIN = "UNCERTAIN"


class CoverageVerdict(StrEnum):
    COVERED = "COVERED"
    PARTIAL = "PARTIAL"
    EXCLUDED = "EXCLUDED"
    UNVERIFIED = "UNVERIFIED"


class Severity(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


_SAFE_FILENAME_RE = re.compile(r"[^A-Za-z0-9._-]+")


def sanitize_filename(raw_filename: str) -> str:
    """Validate and sanitize an uploaded filename against path traversal and control characters."""
    if not raw_filename or "\x00" in raw_filename:
        raise InvalidFilenameError("Filename must be non-empty and contain no null bytes.")
    if ".." in raw_filename or raw_filename.startswith(("/", "\\")):
        raise InvalidFilenameError(f"Filename '{raw_filename}' contains disallowed path traversal.")
    if "/" in raw_filename or "\\" in raw_filename:
        raise InvalidFilenameError(
            f"Filename '{raw_filename}' must not include directory separators."
        )

    posix_name = PurePosixPath(raw_filename).name
    win_name = PureWindowsPath(posix_name).name
    cleaned = _SAFE_FILENAME_RE.sub("_", win_name).strip("._")
    if not cleaned or "." not in cleaned:
        raise InvalidFilenameError(f"Filename '{raw_filename}' must include a valid extension.")
    if len(cleaned) > 180:
        stem, dot, ext = cleaned.rpartition(".")
        cleaned = f"{stem[:160]}{dot}{ext[:16]}"
    return cleaned


class DocumentRecord(BaseModel):
    model_config = ConfigDict(frozen=True)

    document_id: str = Field(min_length=1)
    claim_id: str = Field(min_length=1)
    filename: str = Field(min_length=1)
    content_sha256: str = Field(min_length=64, max_length=64)
    mime_type: str = Field(min_length=1)
    size_bytes: int = Field(ge=0)
    document_type: DocumentType = DocumentType.UNKNOWN
    page_count: int = Field(ge=0, default=1)
    ocr_status: OcrStatus = OcrStatus.NOT_REQUIRED
    ocr_error: str | None = None
    injection_flagged: bool = False
    uploaded_by: str = Field(default="system")
    uploaded_at: datetime = Field(default_factory=utc_now)


class EvidenceChunk(BaseModel):
    model_config = ConfigDict(frozen=True)

    chunk_id: str = Field(min_length=1)
    claim_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    filename: str = Field(min_length=1)
    document_type: DocumentType
    page_number: int = Field(ge=1)
    char_start: int = Field(ge=0)
    char_end: int = Field(ge=0)
    text: str = Field(min_length=1)

    @field_validator("char_end")
    @classmethod
    def _validate_offsets(cls, v: int, info: Any) -> int:
        start = info.data.get("char_start", 0)
        if v < start:
            raise ValueError("char_end must be >= char_start")
        return v


class EvidenceRef(BaseModel):
    model_config = ConfigDict(frozen=True)

    chunk_id: str
    document_id: str
    filename: str
    page_number: int = Field(ge=1)
    char_start: int = Field(ge=0)
    char_end: int = Field(ge=0)
    quote: str = Field(max_length=400)


class ExtractedFact(BaseModel):
    model_config = ConfigDict(frozen=True)

    fact_id: str = Field(default_factory=lambda: f"fact-{uuid4().hex[:12]}")
    claim_id: str
    field_name: str = Field(min_length=1)
    value: str
    normalized_value: str | None = None
    classification: FactClassification
    confidence: float = Field(ge=0.0, le=1.0)
    source_method: str = Field(default="deterministic")
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class EntityLink(BaseModel):
    model_config = ConfigDict(frozen=True)

    link_id: str = Field(default_factory=lambda: f"ent-{uuid4().hex[:12]}")
    claim_id: str
    entity_type: str
    canonical_value: str
    observed_values: list[str]
    document_ids: list[str]
    has_conflict: bool = False
    conflict_detail: str | None = None
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class PolicyCoverageResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    claim_id: str
    policy_number: str | None = None
    verdict: CoverageVerdict = CoverageVerdict.UNVERIFIED
    policy_effective_start: date | None = None
    policy_effective_end: date | None = None
    incident_date: date | None = None
    deductible: Decimal | None = None
    coverage_limit: Decimal | None = None
    claimed_amount: Decimal | None = None
    estimated_payable: Decimal | None = None
    covered_perils: list[str] = Field(default_factory=list)
    exclusions: list[str] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class InconsistencyFinding(BaseModel):
    model_config = ConfigDict(frozen=True)

    finding_id: str = Field(default_factory=lambda: f"inc-{uuid4().hex[:12]}")
    claim_id: str
    code: str
    severity: Severity
    title: str
    description: str
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class MissingEvidenceItem(BaseModel):
    model_config = ConfigDict(frozen=True)

    item_id: str = Field(default_factory=lambda: f"miss-{uuid4().hex[:12]}")
    claim_id: str
    code: str
    required_document_type: DocumentType
    severity: Severity
    reason: str


class RiskIndicator(BaseModel):
    model_config = ConfigDict(frozen=True)

    code: str
    label: str
    weight: int = Field(ge=0, le=100)
    triggered: bool
    score_contribution: int = Field(ge=0, le=100)
    explanation: str
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class RiskAssessment(BaseModel):
    model_config = ConfigDict(frozen=True)

    claim_id: str
    total_score: int = Field(ge=0, le=100)
    severity: Severity
    indicators: list[RiskIndicator] = Field(default_factory=list)
    summary: str
    advisory_only: bool = True
    assessed_at: datetime = Field(default_factory=utc_now)


class ReviewDecision(BaseModel):
    model_config = ConfigDict(frozen=True)

    decision_id: str = Field(default_factory=lambda: f"rev-{uuid4().hex[:12]}")
    claim_id: str
    decision: ReviewDecisionType
    actor_id: str
    actor_type: ActorType
    actor_role: Role
    rationale: str = Field(min_length=3, max_length=2000)
    supervisor_note: str | None = Field(default=None, max_length=2000)
    decided_at: datetime = Field(default_factory=utc_now)


class AuditEvent(BaseModel):
    model_config = ConfigDict(frozen=True)

    event_id: str = Field(default_factory=lambda: f"aud-{uuid4().hex[:12]}")
    claim_id: str | None = None
    event_type: str = Field(min_length=1)
    actor_id: str
    actor_type: ActorType
    correlation_id: str = Field(default="")
    summary: str
    metadata: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)


class Claim(BaseModel):
    """Aggregate root enforcing claim lifecycle and human-in-the-loop governance invariants."""

    claim_id: str = Field(default_factory=lambda: f"CLM-{uuid4().hex[:8].upper()}")
    external_reference: str | None = None
    policy_number: str | None = None
    claimant_name: str | None = None
    incident_type: str | None = None
    status: ClaimStatus = ClaimStatus.INTAKE
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    review_decisions: list[ReviewDecision] = Field(default_factory=list)

    def transition_to(self, target: ClaimStatus) -> None:
        """Transition claim status if permitted by the state machine."""
        allowed = ALLOWED_TRANSITIONS.get(self.status, frozenset())
        if target not in allowed:
            raise InvalidStateTransitionError(self.status.value, target.value)
        self.status = target
        self.updated_at = utc_now()

    def mark_extracted(self) -> None:
        """Record that structured extraction & analysis completed."""
        self.transition_to(ClaimStatus.EXTRACTED)

    def submit_for_review(self) -> None:
        """Move claim into human review queue."""
        self.transition_to(ClaimStatus.IN_REVIEW)

    def record_review_decision(
        self,
        actor: Actor,
        decision: ReviewDecisionType,
        rationale: str,
        supervisor_note: str | None = None,
    ) -> ReviewDecision:
        """Enforce human-only approval/rejection/payment governance invariant.

        CRITICAL INVARIANT:
        - Only HUMAN actors (never AI_SYSTEM or SERVICE) may approve, reject,
          request info, or authorize payment.
        - Actor role must be REVIEWER or ADMIN.
        - Claim must currently be in IN_REVIEW status.
        """
        if actor.actor_type != ActorType.HUMAN:
            raise AutonomousDecisionProhibitedError(
                f"Actor '{actor.actor_id}' ({actor.actor_type.value}) is prohibited from "
                f"executing decision '{decision.value}'. Only human reviewers may decide claims."
            )

        if actor.role not in DECISION_AUTHORIZED_ROLES:
            raise UnauthorizedDecisionError(
                f"Actor '{actor.actor_id}' with role '{actor.role.value}' is not authorized to "
                f"record review decisions. Required role: reviewer or admin."
            )

        cleaned_rationale = rationale.strip()
        if len(cleaned_rationale) < 3:
            raise ValueError("Review decision rationale must be at least 3 characters.")

        target_status_map: dict[ReviewDecisionType, ClaimStatus] = {
            ReviewDecisionType.APPROVE: ClaimStatus.APPROVED,
            ReviewDecisionType.AUTHORIZE_PAYMENT: ClaimStatus.APPROVED,
            ReviewDecisionType.REJECT: ClaimStatus.REJECTED,
            ReviewDecisionType.REQUEST_INFO: ClaimStatus.NEEDS_INFO,
        }
        target_status = target_status_map[decision]

        if self.status != ClaimStatus.IN_REVIEW:
            raise InvalidStateTransitionError(
                self.status.value,
                target_status.value,
                reason="Claim must be in IN_REVIEW status before a human decision can be recorded.",
            )

        self.transition_to(target_status)
        record = ReviewDecision(
            claim_id=self.claim_id,
            decision=decision,
            actor_id=actor.actor_id,
            actor_type=actor.actor_type,
            actor_role=actor.role,
            rationale=cleaned_rationale,
            supervisor_note=supervisor_note.strip() if supervisor_note else None,
        )
        self.review_decisions.append(record)
        return record
