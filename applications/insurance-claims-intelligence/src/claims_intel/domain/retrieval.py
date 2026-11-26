"""Hybrid retrieval engine: BM25 lexical scoring + Cosine Vector similarity + RRF fusion."""

from __future__ import annotations

import math
import re
from collections import Counter
from collections.abc import Sequence

from pydantic import BaseModel, ConfigDict, Field

from claims_intel.domain.models import DocumentType, EvidenceChunk

_TOKEN_RE = re.compile(r"[A-Za-z0-9]+(?:[-_/][A-Za-z0-9]+)*")
_STOPWORDS: frozenset[str] = frozenset(
    {
        "a",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "by",
        "for",
        "from",
        "has",
        "have",
        "in",
        "is",
        "it",
        "of",
        "on",
        "or",
        "that",
        "the",
        "this",
        "to",
        "was",
        "were",
        "what",
        "when",
        "where",
        "which",
        "who",
        "with",
    }
)


def tokenize_for_bm25(text: str) -> list[str]:
    """Tokenize text for BM25, preserving compound IDs (e.g., POL-2026-1001) and sub-tokens."""
    tokens: list[str] = []
    for match in _TOKEN_RE.finditer(text.lower()):
        tok = match.group(0)
        if tok in _STOPWORDS:
            continue
        tokens.append(tok)
        if "-" in tok or "_" in tok or "/" in tok:
            for part in re.split(r"[-_/]", tok):
                if part and part not in _STOPWORDS and len(part) > 1:
                    tokens.append(part)
    return tokens


def cosine_similarity(vec_a: Sequence[float], vec_b: Sequence[float]) -> float:
    """Compute cosine similarity between two equal-length dense vectors."""
    if not vec_a or not vec_b or len(vec_a) != len(vec_b):
        return 0.0
    dot = 0.0
    norm_a = 0.0
    norm_b = 0.0
    for a, b in zip(vec_a, vec_b, strict=True):
        dot += a * b
        norm_a += a * a
        norm_b += b * b
    if norm_a <= 0.0 or norm_b <= 0.0:
        return 0.0
    return dot / (math.sqrt(norm_a) * math.sqrt(norm_b))


class IndexedChunk(BaseModel):
    model_config = ConfigDict(frozen=True)

    chunk: EvidenceChunk
    embedding: list[float]


class RetrievalHit(BaseModel):
    model_config = ConfigDict(frozen=True)

    chunk: EvidenceChunk
    bm25_score: float = Field(ge=0.0)
    vector_score: float
    rrf_score: float = Field(ge=0.0)
    bm25_rank: int | None = None
    vector_rank: int | None = None


class HybridRetriever:
    """Deterministic hybrid retrieval combining BM25 + dense cosine similarity via RRF."""

    def __init__(
        self,
        *,
        k1: float = 1.5,
        b: float = 0.75,
        rrf_k: int = 60,
        bm25_weight: float = 1.0,
        vector_weight: float = 1.0,
    ) -> None:
        self.k1 = k1
        self.b = b
        self.rrf_k = rrf_k
        self.bm25_weight = bm25_weight
        self.vector_weight = vector_weight

    def search(
        self,
        *,
        query: str,
        query_embedding: Sequence[float],
        corpus: Sequence[IndexedChunk],
        top_k: int = 5,
        claim_id: str | None = None,
        document_types: set[DocumentType] | None = None,
    ) -> list[RetrievalHit]:
        """Search corpus with optional metadata filters and return top-k RRF-ranked hits."""
        filtered = [
            item
            for item in corpus
            if (claim_id is None or item.chunk.claim_id == claim_id)
            and (document_types is None or item.chunk.document_type in document_types)
        ]
        if not filtered or top_k <= 0:
            return []

        bm25_scores = self._score_bm25(query, [item.chunk.text for item in filtered])
        vector_scores = [
            cosine_similarity(query_embedding, item.embedding) for item in filtered
        ]

        # Build 1-based rankings for lexical (strictly positive BM25) and vector (positive cosine)
        bm25_order = sorted(
            (idx for idx, s in enumerate(bm25_scores) if s > 0.0),
            key=lambda i: (-bm25_scores[i], filtered[i].chunk.chunk_id),
        )
        vector_order = sorted(
            (idx for idx, s in enumerate(vector_scores) if s > 0.0),
            key=lambda i: (-vector_scores[i], filtered[i].chunk.chunk_id),
        )

        bm25_ranks: dict[int, int] = {
            idx: rank for rank, idx in enumerate(bm25_order, start=1)
        }
        vector_ranks: dict[int, int] = {
            idx: rank for rank, idx in enumerate(vector_order, start=1)
        }

        hits: list[RetrievalHit] = []
        for idx, item in enumerate(filtered):
            b_rank = bm25_ranks.get(idx)
            v_rank = vector_ranks.get(idx)
            rrf = 0.0
            if b_rank is not None:
                rrf += self.bm25_weight / (self.rrf_k + b_rank)
            if v_rank is not None:
                rrf += self.vector_weight / (self.rrf_k + v_rank)

            if rrf <= 0.0:
                continue

            hits.append(
                RetrievalHit(
                    chunk=item.chunk,
                    bm25_score=round(bm25_scores[idx], 6),
                    vector_score=round(vector_scores[idx], 6),
                    rrf_score=round(rrf, 6),
                    bm25_rank=b_rank,
                    vector_rank=v_rank,
                )
            )

        hits.sort(
            key=lambda h: (-h.rrf_score, -h.bm25_score, -h.vector_score, h.chunk.chunk_id)
        )
        return hits[:top_k]

    def _score_bm25(self, query: str, documents: Sequence[str]) -> list[float]:
        """Compute Okapi BM25 scores with real IDF, TF saturation, and length normalization."""
        query_terms = tokenize_for_bm25(query)
        if not query_terms or not documents:
            return [0.0] * len(documents)

        doc_tokens: list[list[str]] = [tokenize_for_bm25(doc) for doc in documents]
        doc_counters: list[Counter[str]] = [Counter(toks) for toks in doc_tokens]
        num_docs = len(documents)
        avg_dl = sum(len(toks) for toks in doc_tokens) / max(num_docs, 1)
        if avg_dl <= 0.0:
            return [0.0] * num_docs

        # Document frequency for unique query terms
        unique_query_terms = set(query_terms)
        df: dict[str, int] = {
            term: sum(1 for c in doc_counters if c.get(term, 0) > 0)
            for term in unique_query_terms
        }

        scores: list[float] = []
        for toks, counter in zip(doc_tokens, doc_counters, strict=True):
            dl = len(toks)
            score = 0.0
            for term in query_terms:
                tf = counter.get(term, 0)
                if tf == 0:
                    continue
                term_df = df[term]
                idf = math.log(((num_docs - term_df + 0.5) / (term_df + 0.5)) + 1.0)
                denom = tf + self.k1 * (1.0 - self.b + self.b * (dl / avg_dl))
                score += idf * ((tf * (self.k1 + 1.0)) / denom)
            scores.append(score)
        return scores
