"""Hybrid search and evidence-backed Q&A service with abstention and citation verification."""

from __future__ import annotations

import re

from claims_intel.application.ports import (
    AuditRepository,
    ClaimRepository,
    EmbeddingPort,
    LlmPort,
    QaAnswerResult,
)
from claims_intel.domain.errors import ClaimNotFoundError
from claims_intel.domain.injection import (
    detect_prompt_injection_in_chunks,
    fence_evidence_chunks,
)
from claims_intel.domain.models import (
    Actor,
    AuditEvent,
    DocumentType,
    EvidenceChunk,
    EvidenceRef,
)
from claims_intel.domain.redaction import redact_text
from claims_intel.domain.retrieval import (
    HybridRetriever,
    RetrievalHit,
    tokenize_for_bm25,
)

_WHITESPACE_RE = re.compile(r"\s+")
_NUMERIC_TOKEN_RE = re.compile(r"\d+(?:[.,:/-]\d+)*")
_ELLIPSIS_SPLIT_RE = re.compile(r"\s*(?:\.\.\.|…)\s*")


def _normalize_whitespace(text: str) -> str:
    return _WHITESPACE_RE.sub(" ", text).strip().lower()


def _extract_numeric_tokens(text: str) -> set[str]:
    """Extract normalized numeric literals (amounts, dates, IDs) to catch altered figures."""
    tokens: set[str] = set()
    for raw in _NUMERIC_TOKEN_RE.findall(text):
        cleaned = raw.replace(",", "")
        if cleaned.endswith(".00") and len(cleaned) > 3:
            cleaned = cleaned[:-3]
        tokens.add(cleaned)
    return tokens


def verify_citation_quote(quote: str, chunk: EvidenceChunk) -> tuple[bool, int, int]:
    """Verify a cited quote is genuinely grounded in the cited chunk's source text.

    Prevents an LLM from attaching a valid retrieved ``chunk_id`` to a fabricated quote,
    an altered numeric amount/date, or text misattributed from a different chunk.

    Returns ``(is_verified, char_start, char_end)`` using document-relative character offsets.
    """
    norm_quote = _normalize_whitespace(quote)
    norm_chunk = _normalize_whitespace(chunk.text)
    if not norm_quote or not norm_chunk:
        return False, chunk.char_start, chunk.char_end

    # 1. Verbatim substring match (case/whitespace normalized)
    raw_lower = chunk.text.lower()
    raw_idx = raw_lower.find(quote.strip().lower())
    if raw_idx >= 0:
        span_start = chunk.char_start + raw_idx
        span_end = min(chunk.char_end, span_start + len(quote.strip()))
        return True, span_start, span_end

    if norm_quote in norm_chunk:
        return True, chunk.char_start, chunk.char_end

    # 2. Ellipsis-joined verbatim segments from the same chunk
    segments = [seg.strip() for seg in _ELLIPSIS_SPLIT_RE.split(norm_quote) if seg.strip()]
    if len(segments) > 1 and all(seg in norm_chunk for seg in segments):
        return True, chunk.char_start, chunk.char_end

    # 3. Strict numeric + high lexical overlap fallback (tolerates minor punctuation edits only)
    quote_numbers = _extract_numeric_tokens(norm_quote)
    chunk_numbers = _extract_numeric_tokens(norm_chunk)
    if quote_numbers and not quote_numbers.issubset(chunk_numbers):
        return False, chunk.char_start, chunk.char_end

    quote_tokens = tokenize_for_bm25(norm_quote)
    chunk_token_set = set(tokenize_for_bm25(norm_chunk))
    if len(quote_tokens) < 2:
        return False, chunk.char_start, chunk.char_end

    matched_tokens = sum(1 for tok in quote_tokens if tok in chunk_token_set)
    overlap_ratio = matched_tokens / len(quote_tokens)
    if overlap_ratio >= 0.85 and matched_tokens >= 2:
        return True, chunk.char_start, chunk.char_end

    return False, chunk.char_start, chunk.char_end


class QaService:
    """Executes hybrid retrieval and grounded Q&A with strict anti-hallucination guardrails."""

    def __init__(
        self,
        *,
        claim_repo: ClaimRepository,
        audit_repo: AuditRepository,
        embedder: EmbeddingPort,
        llm: LlmPort,
        retriever: HybridRetriever | None = None,
        min_evidence_score: float = 0.015,
        min_lexical_overlap: float = 0.18,
        default_top_k: int = 5,
    ) -> None:
        self._claim_repo = claim_repo
        self._audit_repo = audit_repo
        self._embedder = embedder
        self._llm = llm
        self._retriever = retriever or HybridRetriever()
        self._min_evidence_score = min_evidence_score
        self._min_lexical_overlap = min_lexical_overlap
        self._default_top_k = default_top_k

    def search_claim(
        self,
        *,
        claim_id: str,
        query: str,
        top_k: int = 5,
        document_types: set[DocumentType] | None = None,
    ) -> list[RetrievalHit]:
        claim = self._claim_repo.get_claim(claim_id)
        if claim is None:
            raise ClaimNotFoundError(claim_id)

        corpus = self._claim_repo.list_indexed_chunks(claim_id, document_types=document_types)
        if not corpus:
            return []

        vectors, _ = self._embedder.embed_texts([query])
        return self._retriever.search(
            query=query,
            query_embedding=vectors[0],
            corpus=corpus,
            top_k=top_k,
            claim_id=claim_id,
            document_types=document_types,
        )

    def answer_question(
        self,
        *,
        claim_id: str,
        question: str,
        actor: Actor,
        top_k: int | None = None,
        document_types: set[DocumentType] | None = None,
        correlation_id: str = "",
    ) -> QaAnswerResult:
        hits = self.search_claim(
            claim_id=claim_id,
            query=question,
            top_k=top_k or self._default_top_k,
            document_types=document_types,
        )

        # Grounding gate 1: Must have retrieved hits meeting score + evidence overlap thresholds
        if not hits or not self._has_sufficient_evidence(question, hits):
            abstain = QaAnswerResult(
                claim_id=claim_id,
                question=question,
                status="UNANSWERABLE",
                answer=(
                    "Insufficient grounded evidence in the claim file to answer this question "
                    "reliably. Please upload supporting documentation or verify manually."
                ),
                abstained=True,
                abstain_reason=(
                    "Retrieved evidence did not meet minimum relevance and coverage thresholds."
                ),
                confidence=0.0,
                citations=[],
            )
            self._record_qa_audit(
                claim_id=claim_id,
                actor=actor,
                question=question,
                result=abstain,
                correlation_id=correlation_id,
            )
            return abstain

        retrieved_chunks = [h.chunk for h in hits]
        retrieved_map = {c.chunk_id: c for c in retrieved_chunks}

        # Security gate: scan retrieved chunks for prompt injection and fence untrusted text
        injection_report = detect_prompt_injection_in_chunks(retrieved_chunks)
        injection_warnings = [
            f"[{f.category}] chunk={f.chunk_id}: {f.matched_excerpt}"
            for f in injection_report.findings
        ]
        fenced_evidence = fence_evidence_chunks(retrieved_chunks)

        llm_result = self._llm.answer_question(
            question=question,
            fenced_evidence=fenced_evidence,
            chunks=retrieved_chunks,
        )
        llm_out = llm_result.output

        # Grounding gate 2: Verify EVERY cited chunk_id belongs to the retrieved set
        # AND that the cited quote is grounded in that specific chunk's text.
        verified_citations: list[EvidenceRef] = []
        seen_chunk_ids: set[str] = set()
        for cite in llm_out.citations:
            chunk = retrieved_map.get(cite.chunk_id)
            if chunk is None or cite.chunk_id in seen_chunk_ids:
                continue
            is_grounded, span_start, span_end = verify_citation_quote(cite.quote, chunk)
            if not is_grounded:
                continue
            seen_chunk_ids.add(cite.chunk_id)
            verified_citations.append(
                EvidenceRef(
                    chunk_id=chunk.chunk_id,
                    document_id=chunk.document_id,
                    filename=chunk.filename,
                    page_number=chunk.page_number,
                    char_start=span_start,
                    char_end=span_end,
                    quote=cite.quote[:350],
                )
            )

        if llm_out.abstained or not verified_citations:
            reason = llm_out.abstain_reason or (
                "No verified evidence citations matched both retrieved chunk IDs and source text."
            )
            result = QaAnswerResult(
                claim_id=claim_id,
                question=question,
                status="UNANSWERABLE",
                answer=(
                    "Unable to provide a verified evidence-backed answer from the claim documents."
                ),
                abstained=True,
                abstain_reason=reason,
                confidence=0.0,
                citations=[],
                prompt_injection_detected=injection_report.detected,
                injection_warnings=injection_warnings,
                usage=llm_result.usage,
            )
        else:
            result = QaAnswerResult(
                claim_id=claim_id,
                question=question,
                status="ANSWERED",
                answer=llm_out.answer,
                abstained=False,
                abstain_reason=None,
                confidence=llm_out.confidence,
                citations=verified_citations,
                extracted_facts_summary=llm_out.extracted_facts_summary,
                inferences_summary=llm_out.inferences_summary,
                prompt_injection_detected=injection_report.detected,
                injection_warnings=injection_warnings,
                usage=llm_result.usage,
            )

        self._record_qa_audit(
            claim_id=claim_id,
            actor=actor,
            question=question,
            result=result,
            correlation_id=correlation_id,
        )
        return result

    def _has_sufficient_evidence(self, question: str, hits: list[RetrievalHit]) -> bool:
        top_hit = hits[0]
        if top_hit.rrf_score < self._min_evidence_score:
            return False

        query_terms = set(tokenize_for_bm25(question))
        if not query_terms:
            return False

        salient_terms = {t for t in query_terms if len(t) >= 3} or query_terms
        generic_terms = {
            "claim",
            "claims",
            "policy",
            "number",
            "date",
            "report",
            "document",
            "documents",
            "form",
            "file",
            "status",
            "details",
            "information",
        }
        specific_terms = salient_terms - generic_terms or salient_terms

        combined_tokens: set[str] = set()
        for hit in hits[:3]:
            combined_tokens.update(tokenize_for_bm25(hit.chunk.text))

        has_lexical_hit = any(h.bm25_score > 0.0 for h in hits[:3])
        if not has_lexical_hit:
            return False

        overall_overlap = len(salient_terms & combined_tokens) / len(salient_terms)
        specific_overlap = len(specific_terms & combined_tokens) / len(specific_terms)
        min_specific = max(self._min_lexical_overlap, 0.34)
        return overall_overlap >= self._min_lexical_overlap and specific_overlap >= min_specific

    def _record_qa_audit(
        self,
        *,
        claim_id: str,
        actor: Actor,
        question: str,
        result: QaAnswerResult,
        correlation_id: str,
    ) -> None:
        self._audit_repo.record(
            AuditEvent(
                claim_id=claim_id,
                event_type="CLAIM_QA_QUERIED",
                actor_id=actor.actor_id,
                actor_type=actor.actor_type,
                correlation_id=correlation_id,
                summary=redact_text(
                    f"Q&A query on claim {claim_id}: status={result.status}, "
                    f"citations={len(result.citations)}, "
                    f"injection_detected={result.prompt_injection_detected}."
                ),
                metadata={
                    "question": redact_text(question)[:240],
                    "status": result.status,
                    "abstained": result.abstained,
                    "citation_chunk_ids": [c.chunk_id for c in result.citations],
                    "prompt_injection_detected": result.prompt_injection_detected,
                    "provider": result.usage.provider if result.usage else "none",
                    "model": result.usage.model if result.usage else "none",
                    "latency_ms": round(result.usage.latency_ms, 2) if result.usage else 0.0,
                    "prompt_tokens": result.usage.prompt_tokens if result.usage else 0,
                    "completion_tokens": result.usage.completion_tokens if result.usage else 0,
                },
            )
        )
