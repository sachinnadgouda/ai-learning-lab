"""Unit tests for page-aware provenance chunking and BM25 + Cosine Vector RRF hybrid retrieval."""

from __future__ import annotations

from claims_intel.domain.chunking import ExtractedPage, chunk_pages
from claims_intel.domain.models import DocumentType, EvidenceChunk
from claims_intel.domain.retrieval import HybridRetriever, IndexedChunk, cosine_similarity
from claims_intel.infrastructure.ai.providers import LocalDeterministicEmbeddingProvider


def test_chunk_pages_preserves_exact_page_and_character_offsets() -> None:
    page1_text = "Header: Policy Schedule\n" + ("Clause A covers hail and windshield. " * 25)
    page2_text = (
        "Page 2 Exclusions\n"
        + ("Commercial racing and intentional damage excluded. " * 12)
    )
    pages = [
        ExtractedPage(page_number=1, text=page1_text),
        ExtractedPage(page_number=2, text=page2_text),
    ]

    chunks = chunk_pages(
        claim_id="CLM-1",
        document_id="doc-abc",
        filename="policy.pdf",
        document_type=DocumentType.POLICY_SCHEDULE,
        pages=pages,
        chunk_size_chars=240,
        chunk_overlap_chars=40,
    )

    assert len(chunks) >= 4
    page_map = {1: page1_text, 2: page2_text}
    for chunk in chunks:
        src = page_map[chunk.page_number]
        assert src[chunk.char_start : chunk.char_end] == chunk.text
    assert {c.page_number for c in chunks} == {1, 2}


def test_cosine_similarity_properties() -> None:
    assert abs(cosine_similarity([1.0, 0.0], [1.0, 0.0]) - 1.0) < 1e-6
    assert abs(cosine_similarity([1.0, 0.0], [0.0, 1.0])) < 1e-6
    assert cosine_similarity([], []) == 0.0


def test_hybrid_rrf_retrieval_ranks_relevant_chunk_and_filters_metadata() -> None:
    embedder = LocalDeterministicEmbeddingProvider(dim=128)
    raw_chunks = [
        EvidenceChunk(
            chunk_id="c1",
            claim_id="CLM-1",
            document_id="d1",
            filename="policy_schedule.txt",
            document_type=DocumentType.POLICY_SCHEDULE,
            page_number=1,
            char_start=0,
            char_end=80,
            text="Policy POL-AUTO-2026-1001 deductible is $500 and coverage limit is $50,000.",
        ),
        EvidenceChunk(
            chunk_id="c2",
            claim_id="CLM-1",
            document_id="d2",
            filename="repair_invoice.json",
            document_type=DocumentType.INVOICE,
            page_number=1,
            char_start=0,
            char_end=90,
            text=(
                "Alpine AutoGlass replaced OEM acoustic windshield and recalibrated "
                "ADAS camera for $3,250."
            ),
        ),
        EvidenceChunk(
            chunk_id="c3",
            claim_id="CLM-OTHER",
            document_id="d3",
            filename="other_policy.txt",
            document_type=DocumentType.POLICY_SCHEDULE,
            page_number=1,
            char_start=0,
            char_end=70,
            text="Other claim deductible is $2,500 on POL-OTHER-9999.",
        ),
    ]

    vecs, _ = embedder.embed_texts([c.text for c in raw_chunks])
    corpus = [IndexedChunk(chunk=c, embedding=v) for c, v in zip(raw_chunks, vecs, strict=True)]

    retriever = HybridRetriever(rrf_k=60, bm25_weight=1.0, vector_weight=1.0)
    q_vec, _ = embedder.embed_texts(["What is the deductible for POL-AUTO-2026-1001?"])
    hits = retriever.search(
        query="What is the deductible for POL-AUTO-2026-1001?",
        query_embedding=q_vec[0],
        corpus=corpus,
        top_k=3,
        claim_id="CLM-1",
    )

    assert hits
    assert hits[0].chunk.chunk_id == "c1"
    assert hits[0].bm25_score > 0.0
    assert hits[0].vector_score > 0.0
    assert hits[0].rrf_score > 0.0
    # Must exclude CLM-OTHER
    assert all(h.chunk.claim_id == "CLM-1" for h in hits)

    # Filter by document_type=INVOICE
    inv_hits = retriever.search(
        query="windshield ADAS camera invoice",
        query_embedding=embedder.embed_texts(["windshield ADAS camera invoice"])[0][0],
        corpus=corpus,
        top_k=3,
        claim_id="CLM-1",
        document_types={DocumentType.INVOICE},
    )
    assert len(inv_hits) == 1
    assert inv_hits[0].chunk.chunk_id == "c2"
