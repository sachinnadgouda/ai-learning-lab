"""Document ingestion use case: validation, parsing, OCR fallback, chunking, embedding."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from claims_intel.application.ports import (
    AuditRepository,
    ClaimRepository,
    DocumentParserPort,
    EmbeddingPort,
)
from claims_intel.domain.chunking import chunk_pages
from claims_intel.domain.errors import (
    ClaimNotFoundError,
    DuplicateDocumentError,
    PayloadTooLargeError,
)
from claims_intel.domain.injection import detect_prompt_injection_in_chunks
from claims_intel.domain.models import (
    Actor,
    AuditEvent,
    DocumentRecord,
    DocumentType,
    sanitize_filename,
)
from claims_intel.domain.redaction import redact_text
from claims_intel.domain.retrieval import IndexedChunk
from claims_intel.domain.rules import classify_document


@dataclass(frozen=True)
class IngestionOutcome:
    document: DocumentRecord
    chunk_count: int
    deduplicated: bool
    injection_warnings: list[str]


class IngestionService:
    """Validates, parses, classifies, chunks, embeds, and persists claim documents."""

    def __init__(
        self,
        *,
        claim_repo: ClaimRepository,
        audit_repo: AuditRepository,
        parser: DocumentParserPort,
        embedder: EmbeddingPort,
        max_upload_bytes: int = 10 * 1024 * 1024,
        chunk_size_chars: int = 700,
        chunk_overlap_chars: int = 120,
    ) -> None:
        self._claim_repo = claim_repo
        self._audit_repo = audit_repo
        self._parser = parser
        self._embedder = embedder
        self._max_upload_bytes = max_upload_bytes
        self._chunk_size_chars = chunk_size_chars
        self._chunk_overlap_chars = chunk_overlap_chars

    def ingest_document(
        self,
        *,
        claim_id: str,
        raw_filename: str,
        content: bytes,
        actor: Actor,
        declared_mime_type: str | None = None,
        explicit_document_type: DocumentType | None = None,
        correlation_id: str = "",
    ) -> IngestionOutcome:
        claim = self._claim_repo.get_claim(claim_id)
        if claim is None:
            raise ClaimNotFoundError(claim_id)

        if len(content) > self._max_upload_bytes:
            raise PayloadTooLargeError(len(content), self._max_upload_bytes)

        safe_filename = sanitize_filename(raw_filename)
        content_sha256 = hashlib.sha256(content).hexdigest()

        # Idempotent content-addressed check per claim
        existing = self._claim_repo.get_document_by_hash(claim_id, content_sha256)
        if existing is not None:
            existing_chunks = [
                c
                for c in self._claim_repo.list_indexed_chunks(claim_id)
                if c.chunk.document_id == existing.document_id
            ]
            return IngestionOutcome(
                document=existing,
                chunk_count=len(existing_chunks),
                deduplicated=True,
                injection_warnings=[],
            )

        parsed = self._parser.parse(
            filename=safe_filename,
            content=content,
            declared_mime_type=declared_mime_type,
        )

        combined_text = "\n".join(p.text for p in parsed.pages)
        doc_type = explicit_document_type or classify_document(
            safe_filename, parsed.mime_type, combined_text
        )
        # Claim-scoped deterministic document_id prevents cross-claim PK collisions
        # when two different claims upload identical policy schedules or template files.
        scoped_digest = hashlib.sha256(
            f"{claim_id}:{content_sha256}".encode()
        ).hexdigest()[:12]
        document_id = f"doc-{scoped_digest}"

        chunks = chunk_pages(
            claim_id=claim_id,
            document_id=document_id,
            filename=safe_filename,
            document_type=doc_type,
            pages=parsed.pages,
            chunk_size_chars=self._chunk_size_chars,
            chunk_overlap_chars=self._chunk_overlap_chars,
        )

        injection_report = detect_prompt_injection_in_chunks(chunks)
        injection_warnings = [
            f"[{f.category}] {f.matched_excerpt}" for f in injection_report.findings
        ]

        indexed_chunks: list[IndexedChunk] = []
        embed_latency_ms = 0.0
        if chunks:
            vectors, usage = self._embedder.embed_texts([c.text for c in chunks])
            embed_latency_ms = usage.latency_ms
            indexed_chunks = [
                IndexedChunk(chunk=chunk, embedding=vec)
                for chunk, vec in zip(chunks, vectors, strict=True)
            ]

        doc_record = DocumentRecord(
            document_id=document_id,
            claim_id=claim_id,
            filename=safe_filename,
            content_sha256=content_sha256,
            mime_type=parsed.mime_type,
            size_bytes=len(content),
            document_type=doc_type,
            page_count=max(len(parsed.pages), 1),
            ocr_status=parsed.ocr_status,
            ocr_error=parsed.ocr_error,
            injection_flagged=injection_report.detected,
            uploaded_by=actor.actor_id,
        )
        try:
            saved_doc = self._claim_repo.save_document_and_chunks(doc_record, indexed_chunks)
        except DuplicateDocumentError as dup:
            existing_doc = (
                dup.existing_document
                if isinstance(dup.existing_document, DocumentRecord)
                else self._claim_repo.get_document_by_hash(claim_id, content_sha256)
            )
            if existing_doc is None:
                raise
            existing_chunks = [
                c
                for c in self._claim_repo.list_indexed_chunks(claim_id)
                if c.chunk.document_id == existing_doc.document_id
            ]
            return IngestionOutcome(
                document=existing_doc,
                chunk_count=len(existing_chunks),
                deduplicated=True,
                injection_warnings=[],
            )

        self._audit_repo.record(
            AuditEvent(
                claim_id=claim_id,
                event_type="DOCUMENT_INGESTED",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=redact_text(
                    f"Ingested '{safe_filename}' ({doc_type.value}, "
                    f"{len(indexed_chunks)} chunks, ocr={parsed.ocr_status.value}, "
                    f"injection_flagged={injection_report.detected})."
                ),
                metadata={
                    "document_id": saved_doc.document_id,
                    "filename": safe_filename,
                    "document_type": doc_type.value,
                    "size_bytes": len(content),
                    "chunk_count": len(indexed_chunks),
                    "ocr_status": parsed.ocr_status.value,
                    "ocr_error": parsed.ocr_error,
                    "injection_flagged": injection_report.detected,
                    "embed_provider": self._embedder.provider_name,
                    "embed_latency_ms": round(embed_latency_ms, 2),
                },
            )
        )

        return IngestionOutcome(
            document=saved_doc,
            chunk_count=len(indexed_chunks),
            deduplicated=False,
            injection_warnings=injection_warnings,
        )
