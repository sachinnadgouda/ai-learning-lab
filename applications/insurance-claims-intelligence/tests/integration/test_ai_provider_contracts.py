"""HTTP contract tests for Ollama & OpenAI-compatible providers."""

from __future__ import annotations

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from claims_intel.domain.errors import AiResponseValidationError, UpstreamAiError
from claims_intel.domain.models import DocumentType, EvidenceChunk
from claims_intel.infrastructure.ai.providers import (
    OllamaProvider,
    OpenAiCompatibleProvider,
)
from claims_intel.infrastructure.api.main import create_app
from claims_intel.infrastructure.config import Settings


def _sample_chunk() -> EvidenceChunk:
    return EvidenceChunk(
        chunk_id="doc-1:p1:c0",
        claim_id="CLM-1",
        document_id="doc-1",
        filename="policy.txt",
        document_type=DocumentType.POLICY_SCHEDULE,
        page_number=1,
        char_start=0,
        char_end=40,
        text="Policy POL-2026-1001 deductible is $500.",
    )


def test_ollama_provider_retries_503_and_parses_structured_qa() -> None:
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        if request.url.path == "/api/embed":
            return httpx.Response(
                200,
                json={"embeddings": [[0.5, 0.5]], "prompt_eval_count": 7},
            )
        if attempts["count"] == 2:
            return httpx.Response(503, text="temporarily unavailable")
        return httpx.Response(
            200,
            json={
                "message": {
                    "content": json.dumps(
                        {
                            "answer": "The deductible is $500.",
                            "abstained": False,
                            "abstain_reason": None,
                            "confidence": 0.92,
                            "citations": [
                                {"chunk_id": "doc-1:p1:c0", "quote": "deductible is $500"}
                            ],
                            "extracted_facts_summary": ["Deductible is $500"],
                            "inferences_summary": [],
                        }
                    )
                },
                "prompt_eval_count": 40,
                "eval_count": 18,
            },
        )

    provider = OllamaProvider(
        base_url="http://mock-ollama.local",
        chat_model="llama3.2:3b",
        embed_model="nomic-embed-text:v1.5",
        max_retries=2,
        backoff_base=0.01,
        transport=httpx.MockTransport(handler),
        sleep_fn=lambda _: None,
    )
    try:
        vecs, embed_usage = provider.embed_texts(["deductible $500"])
        assert vecs == [[0.5, 0.5]]
        assert embed_usage.prompt_tokens == 7

        qa_res = provider.answer_question(
            question="What is the deductible?",
            fenced_evidence="<untrusted_claim_document>...</untrusted_claim_document>",
            chunks=[_sample_chunk()],
        )
        assert qa_res.output.answer == "The deductible is $500."
        assert qa_res.usage.prompt_tokens == 40
        assert qa_res.usage.completion_tokens == 18
        assert attempts["count"] == 3
    finally:
        provider.close()


def test_openai_provider_retries_429_and_rejects_malformed_json() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"Retry-After": "0.01"}, text="rate limited")
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "NOT VALID JSON {"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5},
            },
        )

    provider = OpenAiCompatibleProvider(
        base_url="https://mock-openai.local/v1",
        api_key="test-key",
        chat_model="gpt-4o-mini",
        embed_model="text-embedding-3-small",
        max_retries=2,
        backoff_base=0.01,
        transport=httpx.MockTransport(handler),
        sleep_fn=lambda _: None,
    )
    try:
        with pytest.raises(AiResponseValidationError):
            provider.answer_question(
                question="What is the deductible?",
                fenced_evidence="...",
                chunks=[_sample_chunk()],
            )
        assert calls["n"] == 2
    finally:
        provider.close()


def test_ollama_timeout_maps_to_http_502_rfc9457_and_hallucinated_citations_are_stripped(
    test_settings: Settings,
    adjuster_headers: dict[str, str],
) -> None:
    # 1. Timeout -> UpstreamAiError -> HTTP 502 via FastAPI
    def timeout_handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated upstream read timeout", request=request)

    failing_llm = OllamaProvider(
        base_url="http://mock-ollama.local",
        chat_model="llama3.2:3b",
        embed_model="nomic-embed-text:v1.5",
        max_retries=1,
        backoff_base=0.01,
        transport=httpx.MockTransport(timeout_handler),
        sleep_fn=lambda _: None,
    )

    app_502 = create_app(settings=test_settings, llm_override=failing_llm)
    with TestClient(app_502) as tc:
        tc.post("/api/v1/claims", json={"claim_id": "CLM-502"}, headers=adjuster_headers)
        tc.post(
            "/api/v1/claims/CLM-502/documents",
            files={
                "file": (
                    "policy_schedule.txt",
                    b"Policy Number: POL-2026-1001\nDeductible: $500.00\n",
                    "text/plain",
                )
            },
            headers=adjuster_headers,
        )
        qa_502 = tc.post(
            "/api/v1/claims/CLM-502/qa",
            json={"question": "What is the deductible on policy POL-2026-1001?"},
            headers=adjuster_headers,
        )
        assert qa_502.status_code == 502
        assert qa_502.headers["content-type"].startswith("application/problem+json")
        assert qa_502.json()["error_code"] == "UPSTREAM_AI_UNAVAILABLE"

    # 2. Hallucinated chunk_id returned by LLM -> QaService rejects fabricated citation & abstains!
    def hallucinating_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "message": {
                    "content": json.dumps(
                        {
                            "answer": "Deductible is $500.",
                            "abstained": False,
                            "confidence": 0.9,
                            "citations": [
                                {"chunk_id": "FABRICATED-CHUNK-999", "quote": "fake quote"}
                            ],
                        }
                    )
                }
            },
        )

    hallucinating_llm = OllamaProvider(
        base_url="http://mock-ollama.local",
        chat_model="llama3.2:3b",
        embed_model="nomic-embed-text:v1.5",
        max_retries=0,
        transport=httpx.MockTransport(hallucinating_handler),
        sleep_fn=lambda _: None,
    )
    app_ground = create_app(settings=test_settings, llm_override=hallucinating_llm)
    with TestClient(app_ground) as tc:
        qa_res = tc.post(
            "/api/v1/claims/CLM-502/qa",
            json={"question": "What is the deductible on policy POL-2026-1001?"},
            headers=adjuster_headers,
        )
        assert qa_res.status_code == 200
        assert qa_res.json()["status"] == "UNANSWERABLE"
        assert qa_res.json()["abstained"] is True
        assert qa_res.json()["citations"] == []


def test_direct_timeout_raises_upstream_ai_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    provider = OllamaProvider(
        base_url="http://mock-ollama.local",
        chat_model="llama3.2:3b",
        embed_model="nomic-embed-text:v1.5",
        max_retries=1,
        backoff_base=0.01,
        transport=httpx.MockTransport(handler),
        sleep_fn=lambda _: None,
    )
    try:
        with pytest.raises(UpstreamAiError):
            provider.embed_texts(["hello"])
    finally:
        provider.close()
