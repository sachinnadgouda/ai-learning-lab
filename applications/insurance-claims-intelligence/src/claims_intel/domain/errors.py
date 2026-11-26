"""Domain and application exception hierarchy for Insurance Claims Intelligence."""

from __future__ import annotations


class ClaimsIntelError(Exception):
    """Base error for all claims intelligence exceptions."""

    def __init__(self, message: str, *, code: str = "CLAIMS_INTEL_ERROR") -> None:
        super().__init__(message)
        self.message = message
        self.code = code


class AutonomousDecisionProhibitedError(ClaimsIntelError):
    """Raised when an AI or non-human actor attempts an authoritative claim decision."""

    def __init__(
        self,
        message: str = (
            "AI systems are strictly prohibited from approving, rejecting, or authorizing "
            "payment on insurance claims. A verified human reviewer is required."
        ),
    ) -> None:
        super().__init__(message, code="AUTONOMOUS_DECISION_PROHIBITED")


class UnauthorizedDecisionError(ClaimsIntelError):
    """Raised when a human actor lacks the required role to record a claim decision."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="UNAUTHORIZED_DECISION")


class InvalidStateTransitionError(ClaimsIntelError):
    """Raised when a claim state machine transition is not allowed."""

    def __init__(self, current_status: str, target_status: str, reason: str = "") -> None:
        detail = f"Invalid claim transition from {current_status} to {target_status}"
        if reason:
            detail = f"{detail}: {reason}"
        super().__init__(detail, code="INVALID_STATE_TRANSITION")
        self.current_status = current_status
        self.target_status = target_status


class ClaimNotFoundError(ClaimsIntelError):
    """Raised when a claim ID does not exist."""

    def __init__(self, claim_id: str) -> None:
        super().__init__(f"Claim '{claim_id}' was not found.", code="CLAIM_NOT_FOUND")
        self.claim_id = claim_id


class DuplicateDocumentError(ClaimsIntelError):
    """Raised when a concurrent write attempts to insert a duplicate claim document."""

    def __init__(
        self,
        claim_id: str,
        content_sha256: str,
        *,
        existing_document: object | None = None,
    ) -> None:
        super().__init__(
            f"Document with sha256={content_sha256[:12]} already exists on claim '{claim_id}'.",
            code="DUPLICATE_DOCUMENT",
        )
        self.claim_id = claim_id
        self.content_sha256 = content_sha256
        self.existing_document = existing_document


class InvalidFilenameError(ClaimsIntelError):
    """Raised when an uploaded filename is empty, unsafe, or attempts path traversal."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="INVALID_FILENAME")


class PayloadTooLargeError(ClaimsIntelError):
    """Raised when an uploaded document exceeds MAX_UPLOAD_BYTES."""

    def __init__(self, size_bytes: int, max_bytes: int) -> None:
        super().__init__(
            f"Uploaded file size ({size_bytes} bytes) exceeds maximum allowed ({max_bytes} bytes).",
            code="PAYLOAD_TOO_LARGE",
        )
        self.size_bytes = size_bytes
        self.max_bytes = max_bytes


class UnsupportedDocumentFormatError(ClaimsIntelError):
    """Raised when an uploaded file format/extension or MIME type is unsupported."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="UNSUPPORTED_DOCUMENT_FORMAT")


class InvalidDocumentError(ClaimsIntelError):
    """Raised when a document fails signature validation or is corrupt/unreadable."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="INVALID_DOCUMENT")


class OcrUnavailableError(ClaimsIntelError):
    """Raised when an OCR engine binary or backend is unavailable."""

    def __init__(self, message: str = "OCR engine is unavailable.") -> None:
        super().__init__(message, code="OCR_UNAVAILABLE")


class OcrProcessingError(ClaimsIntelError):
    """Raised when OCR fails while processing an image."""

    def __init__(self, message: str) -> None:
        super().__init__(message, code="OCR_PROCESSING_ERROR")


class UpstreamAiError(ClaimsIntelError):
    """Raised when an external LLM or embedding provider fails after retries."""

    def __init__(self, message: str, *, provider: str = "unknown") -> None:
        super().__init__(message, code="UPSTREAM_AI_UNAVAILABLE")
        self.provider = provider


class AiResponseValidationError(UpstreamAiError):
    """Raised when an LLM returns malformed JSON or schema-invalid output."""

    def __init__(self, message: str, *, provider: str = "unknown") -> None:
        super().__init__(message, provider=provider)
        self.code = "AI_RESPONSE_VALIDATION_ERROR"
