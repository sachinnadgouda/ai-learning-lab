"""Hardening tests for ingestion concurrency, failure recovery, citation grounding, and RBAC."""

from __future__ import annotations

import json
import threading
from collections.abc import Sequence
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import event
from sqlalchemy.exc import OperationalError

from claims_intel.application.ingestion_service import IngestionService
from claims_intel.application.ports import AiCallUsage, EmbeddingPort
from claims_intel.domain.errors import OcrUnavailableError, UpstreamAiError
from claims_intel.domain.models import Actor, ActorType, EvidenceChunk, Role
from claims_intel.infrastructure.ai.providers import (
    LocalDeterministicEmbeddingProvider,
    OllamaProvider,
)
from claims_intel.infrastructure.api.dependencies import build_container
from claims_intel.infrastructure.api.main import create_app
from claims_intel.infrastructure.config import Settings
from claims_intel.infrastructure.parsers.document_parser import MultiFormatDocumentParser
from claims_intel.infrastructure.persistence.database import run_alembic_migrations
from claims_intel.infrastructure.persistence.orm import EvidenceChunkORM


class AlwaysUnavailableOcr:
    """Deterministic OCR stub simulating an environment without Tesseract."""

    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        raise OcrUnavailableError(f"Simulated OCR unavailability for {filename}")


class BarrierEmbedder:
    """Synchronizes two concurrent ingestions past get_document_by_hash before DB commit."""

    def __init__(self, inner: EmbeddingPort, parties: int = 2) -> None:
        self._inner = inner
        self._barrier = threading.Barrier(parties, timeout=5.0)

    @property
    def provider_name(self) -> str:
        return self._inner.provider_name

    @property
    def model_name(self) -> str:
        return self._inner.model_name

    def embed_texts(self, texts: Sequence[str]) -> tuple[list[list[float]], AiCallUsage]:
        # Hold both workers until both have passed the pre-insert dedup check.
        self._barrier.wait()
        return self._inner.embed_texts(texts)


class FlakyEmbedder:
    """Fails the first N embed calls with UpstreamAiError, then delegates cleanly."""

    def __init__(self, inner: EmbeddingPort, fail_times: int = 1) -> None:
        self._inner = inner
        self._remaining_failures = fail_times
        self.calls = 0

    @property
    def provider_name(self) -> str:
        return "flaky-test-embedder"

    @property
    def model_name(self) -> str:
        return "flaky-v1"

    def embed_texts(self, texts: Sequence[str]) -> tuple[list[list[float]], AiCallUsage]:
        self.calls += 1
        if self._remaining_failures > 0:
            self._remaining_failures -= 1
            raise UpstreamAiError(
                "Simulated embedding provider outage",
                provider=self.provider_name,
            )
        return self._inner.embed_texts(texts)


def _human_adjuster() -> Actor:
    return Actor(
        actor_id="human-adjuster",
        actor_type=ActorType.HUMAN,
        role=Role.ADJUSTER,
        display_name="Test Adjuster",
    )


def test_two_concurrent_identical_uploads_deduplicate_atomically(
    test_settings: Settings,
) -> None:
    run_alembic_migrations(test_settings.database_url)
    container = build_container(test_settings, ocr_override=AlwaysUnavailableOcr())
    try:
        actor = _human_adjuster()
        container.review_service.create_claim(actor=actor, claim_id="CLM-CONC-1")

        barrier_embedder = BarrierEmbedder(LocalDeterministicEmbeddingProvider(), parties=2)
        concurrent_ingestion = IngestionService(
            claim_repo=container.claim_repo,
            audit_repo=container.audit_repo,
            parser=MultiFormatDocumentParser(ocr_adapter=AlwaysUnavailableOcr()),
            embedder=barrier_embedder,
        )

        payload = (
            b"POLICY SCHEDULE\n"
            b"Policy Number: POL-CONC-2026-01\n"
            b"Deductible: $500.00\n"
            b"Coverage Limit: $25,000.00\n"
        )

        def _upload(idx: int):
            return concurrent_ingestion.ingest_document(
                claim_id="CLM-CONC-1",
                raw_filename="policy_schedule.txt",
                content=payload,
                actor=actor,
                declared_mime_type="text/plain",
                correlation_id=f"corr-conc-{idx}",
            )

        with ThreadPoolExecutor(max_workers=2) as pool:
            f1 = pool.submit(_upload, 1)
            f2 = pool.submit(_upload, 2)
            outcomes = [f1.result(timeout=10), f2.result(timeout=10)]

        dedup_flags = sorted(o.deduplicated for o in outcomes)
        assert dedup_flags == [False, True]
        assert outcomes[0].document.document_id == outcomes[1].document.document_id
        assert outcomes[0].chunk_count == outcomes[1].chunk_count >= 1

        docs = container.claim_repo.list_documents("CLM-CONC-1")
        chunks = container.claim_repo.list_indexed_chunks("CLM-CONC-1")
        ingested_events = [
            e
            for e in container.audit_repo.list_for_claim("CLM-CONC-1")
            if e.event_type == "DOCUMENT_INGESTED"
        ]
        assert len(docs) == 1
        assert len(chunks) == outcomes[0].chunk_count
        assert len(ingested_events) == 1
    finally:
        container.shutdown()


def test_identical_content_across_distinct_claims_stays_isolated(
    client: TestClient,
    adjuster_headers: dict[str, str],
) -> None:
    payload = b"POLICY SCHEDULE\nPolicy Number: POL-SHARED-99\nDeductible: $750.00\n"
    for cid in ("CLM-ISO-A", "CLM-ISO-B"):
        r_claim = client.post("/api/v1/claims", json={"claim_id": cid}, headers=adjuster_headers)
        assert r_claim.status_code == 201
        r_doc = client.post(
            f"/api/v1/claims/{cid}/documents",
            files={"file": ("policy_schedule.txt", payload, "text/plain")},
            headers=adjuster_headers,
        )
        assert r_doc.status_code == 201
        assert r_doc.json()["deduplicated"] is False

    detail_a = client.get("/api/v1/claims/CLM-ISO-A", headers=adjuster_headers).json()
    detail_b = client.get("/api/v1/claims/CLM-ISO-B", headers=adjuster_headers).json()
    assert len(detail_a["documents"]) == 1
    assert len(detail_b["documents"]) == 1
    assert detail_a["documents"][0]["document_id"] != detail_b["documents"][0]["document_id"]


def test_embedding_provider_failure_leaves_zero_state_and_allows_clean_reingestion(
    test_settings: Settings,
    adjuster_headers: dict[str, str],
) -> None:
    flaky = FlakyEmbedder(LocalDeterministicEmbeddingProvider(), fail_times=1)
    app = create_app(
        settings=test_settings,
        embedder_override=flaky,
        ocr_override=AlwaysUnavailableOcr(),
    )
    payload = (
        b"FIRST NOTICE OF LOSS\n"
        b"Policy Number: POL-EMB-2026-07\n"
        b"Claimant Name: Jordan Blake\n"
        b"Claimed Amount: $1,850.00\n"
    )

    with TestClient(app) as tc:
        assert (
            tc.post("/api/v1/claims", json={"claim_id": "CLM-EMB-1"}, headers=adjuster_headers)
            .status_code
            == 201
        )

        # First upload fails during embedding -> 502 RFC 9457, zero persisted rows
        failed = tc.post(
            "/api/v1/claims/CLM-EMB-1/documents",
            files={"file": ("fnol.txt", payload, "text/plain")},
            headers=adjuster_headers,
        )
        assert failed.status_code == 502
        assert failed.json()["error_code"] == "UPSTREAM_AI_UNAVAILABLE"

        container = app.state.container
        assert container.claim_repo.list_documents("CLM-EMB-1") == []
        assert container.claim_repo.list_indexed_chunks("CLM-EMB-1") == []
        ingested_after_fail = [
            e
            for e in container.audit_repo.list_for_claim("CLM-EMB-1")
            if e.event_type == "DOCUMENT_INGESTED"
        ]
        assert ingested_after_fail == []

        # Re-ingestion after provider recovers succeeds cleanly (not deduplicated)
        recovered = tc.post(
            "/api/v1/claims/CLM-EMB-1/documents",
            files={"file": ("fnol.txt", payload, "text/plain")},
            headers=adjuster_headers,
        )
        assert recovered.status_code == 201
        assert recovered.json()["deduplicated"] is False
        assert recovered.json()["chunk_count"] >= 1
        assert len(container.claim_repo.list_documents("CLM-EMB-1")) == 1


def test_db_failure_during_chunk_persistence_rolls_back_and_recovers_on_reingestion(
    test_settings: Settings,
) -> None:
    run_alembic_migrations(test_settings.database_url)
    container = build_container(test_settings, ocr_override=AlwaysUnavailableOcr())
    try:
        actor = _human_adjuster()
        container.review_service.create_claim(actor=actor, claim_id="CLM-DBFAIL-1")

        # Inject a failure AFTER DocumentORM is flushed, right as EvidenceChunkORM inserts
        should_fail = {"armed": True}

        @event.listens_for(EvidenceChunkORM, "before_insert")
        def _fail_chunk_insert(_mapper, _connection, _target) -> None:
            if should_fail["armed"]:
                should_fail["armed"] = False
                raise OperationalError(
                    "INSERT INTO evidence_chunks ...",
                    {},
                    Exception("simulated disk I/O error mid-transaction"),
                )

        payload = (
            b"REPAIR ESTIMATE\n"
            b"Policy Number: POL-DB-2026-42\n"
            b"Front bumper replacement: $3,200.00\n"
            b"Paint and blend labor: $950.00\n"
        )

        try:
            with pytest.raises(OperationalError):
                container.ingestion_service.ingest_document(
                    claim_id="CLM-DBFAIL-1",
                    raw_filename="repair_estimate.txt",
                    content=payload,
                    actor=actor,
                    declared_mime_type="text/plain",
                )
        finally:
            event.remove(EvidenceChunkORM, "before_insert", _fail_chunk_insert)

        # Rollback must leave ZERO orphaned documents, chunks, or DOCUMENT_INGESTED audits
        assert container.claim_repo.list_documents("CLM-DBFAIL-1") == []
        assert container.claim_repo.list_indexed_chunks("CLM-DBFAIL-1") == []
        assert [
            e
            for e in container.audit_repo.list_for_claim("CLM-DBFAIL-1")
            if e.event_type == "DOCUMENT_INGESTED"
        ] == []

        # Re-ingesting the same file after recovery persists atomically and is searchable
        outcome = container.ingestion_service.ingest_document(
            claim_id="CLM-DBFAIL-1",
            raw_filename="repair_estimate.txt",
            content=payload,
            actor=actor,
            declared_mime_type="text/plain",
        )
        assert outcome.deduplicated is False
        assert outcome.chunk_count >= 1
        assert len(container.claim_repo.list_documents("CLM-DBFAIL-1")) == 1
        assert len(container.claim_repo.list_indexed_chunks("CLM-DBFAIL-1")) == outcome.chunk_count
    finally:
        container.shutdown()


def test_citation_verification_rejects_fabricated_altered_and_misattributed_quotes(
    test_settings: Settings,
    adjuster_headers: dict[str, str],
) -> None:
    run_alembic_migrations(test_settings.database_url)

    def _make_mock_llm(citations_factory):
        def handler(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content.decode("utf-8"))
            user_prompt = body["messages"][-1]["content"]
            citations = citations_factory(user_prompt)
            return httpx.Response(
                200,
                json={
                    "message": {
                        "content": json.dumps(
                            {
                                "answer": "The deductible is $500.00 under policy POL-CITE-2026.",
                                "abstained": False,
                                "abstain_reason": None,
                                "confidence": 0.91,
                                "citations": citations,
                                "extracted_facts_summary": ["Deductible stated"],
                                "inferences_summary": [],
                            }
                        )
                    }
                },
            )

        return OllamaProvider(
            base_url="http://mock-ollama.local",
            chat_model="llama3.2:3b",
            embed_model="nomic-embed-text:v1.5",
            max_retries=0,
            transport=httpx.MockTransport(handler),
            sleep_fn=lambda _: None,
        )

    # Seed a claim with two distinct documents
    seed_app = create_app(settings=test_settings, ocr_override=AlwaysUnavailableOcr())
    with TestClient(seed_app) as tc:
        tc.post("/api/v1/claims", json={"claim_id": "CLM-CITE-1"}, headers=adjuster_headers)
        tc.post(
            "/api/v1/claims/CLM-CITE-1/documents",
            files={
                "file": (
                    "policy_schedule.txt",
                    b"POLICY SCHEDULE\nPolicy Number: POL-CITE-2026\nDeductible: $500.00\n",
                    "text/plain",
                )
            },
            headers=adjuster_headers,
        )
        tc.post(
            "/api/v1/claims/CLM-CITE-1/documents",
            files={
                "file": (
                    "invoice_shop.txt",
                    b"REPAIR INVOICE\nVendor: Summit Auto Body\nTotal Billed: $2,900.00\n",
                    "text/plain",
                )
            },
            headers=adjuster_headers,
        )
        chunks: list[EvidenceChunk] = [
            ic.chunk for ic in seed_app.state.container.claim_repo.list_indexed_chunks("CLM-CITE-1")
        ]
        policy_chunk = next(c for c in chunks if c.filename == "policy_schedule.txt")
        invoice_chunk = next(c for c in chunks if c.filename == "invoice_shop.txt")

    # Case 1: Valid chunk_id, completely fabricated quote -> MUST abstain!
    llm_fabricated = _make_mock_llm(
        lambda _: [
            {
                "chunk_id": policy_chunk.chunk_id,
                "quote": "Policy waives all deductibles for luxury yacht racing events",
            }
        ]
    )
    with TestClient(
        create_app(
            settings=test_settings,
            llm_override=llm_fabricated,
            ocr_override=AlwaysUnavailableOcr(),
        )
    ) as tc:
        res = tc.post(
            "/api/v1/claims/CLM-CITE-1/qa",
            json={"question": "What is the deductible on policy POL-CITE-2026?"},
            headers=adjuster_headers,
        )
        assert res.status_code == 200
        assert res.json()["status"] == "UNANSWERABLE"
        assert res.json()["abstained"] is True
        assert res.json()["citations"] == []

    # Case 2: Valid chunk_id, altered numeric figure ($50.00 instead of $500.00) -> MUST abstain!
    llm_altered_num = _make_mock_llm(
        lambda _: [
            {
                "chunk_id": policy_chunk.chunk_id,
                "quote": "Policy Number: POL-CITE-2026 Deductible: $50.00",
            }
        ]
    )
    with TestClient(
        create_app(
            settings=test_settings,
            llm_override=llm_altered_num,
            ocr_override=AlwaysUnavailableOcr(),
        )
    ) as tc:
        res = tc.post(
            "/api/v1/claims/CLM-CITE-1/qa",
            json={"question": "What is the deductible on policy POL-CITE-2026?"},
            headers=adjuster_headers,
        )
        assert res.status_code == 200
        assert res.json()["abstained"] is True
        assert res.json()["citations"] == []

    # Case 3: Misattributed provenance (real quote from invoice attached to policy chunk_id)
    llm_misattributed = _make_mock_llm(
        lambda _: [
            {
                "chunk_id": policy_chunk.chunk_id,
                "quote": "Vendor: Summit Auto Body Total Billed: $2,900.00",
            }
        ]
    )
    with TestClient(
        create_app(
            settings=test_settings,
            llm_override=llm_misattributed,
            ocr_override=AlwaysUnavailableOcr(),
        )
    ) as tc:
        res = tc.post(
            "/api/v1/claims/CLM-CITE-1/qa",
            json={"question": "What is the deductible on policy POL-CITE-2026?"},
            headers=adjuster_headers,
        )
        assert res.status_code == 200
        assert res.json()["abstained"] is True
        assert res.json()["citations"] == []

    # Case 4: Mixed citations -> strips fabricated citation, retains grounded quote with offsets
    llm_mixed = _make_mock_llm(
        lambda _: [
            {
                "chunk_id": invoice_chunk.chunk_id,
                "quote": "Fabricated statement never in invoice",
            },
            {
                "chunk_id": policy_chunk.chunk_id,
                "quote": "Deductible: $500.00",
            },
        ]
    )
    with TestClient(
        create_app(
            settings=test_settings,
            llm_override=llm_mixed,
            ocr_override=AlwaysUnavailableOcr(),
        )
    ) as tc:
        res = tc.post(
            "/api/v1/claims/CLM-CITE-1/qa",
            json={"question": "What is the deductible on policy POL-CITE-2026?"},
            headers=adjuster_headers,
        )
        assert res.status_code == 200
        body = res.json()
        assert body["status"] == "ANSWERED"
        assert len(body["citations"]) == 1
        assert body["citations"][0]["chunk_id"] == policy_chunk.chunk_id
        assert body["citations"][0]["quote"] == "Deductible: $500.00"
        assert (
            body["citations"][0]["char_end"] - body["citations"][0]["char_start"]
            == len("Deductible: $500.00")
        )


def test_authentication_and_role_boundaries_preserve_human_only_decision_invariant(
    client: TestClient,
    adjuster_headers: dict[str, str],
    reviewer_headers: dict[str, str],
    admin_headers: dict[str, str],
    ai_service_headers: dict[str, str],
) -> None:
    # Unauthenticated calls fail across protected routes
    assert client.post("/api/v1/claims", json={"claim_id": "CLM-AUTH-0"}).status_code == 401
    assert (
        client.post(
            "/api/v1/claims/CLM-AUTH-0/review",
            json={"decision": "APPROVE", "rationale": "No auth header"},
        ).status_code
        == 401
    )

    # AI service cannot mutate claims, upload documents, submit for review, or decide claims
    assert (
        client.post(
            "/api/v1/claims", json={"claim_id": "CLM-AI-FORBIDDEN"}, headers=ai_service_headers
        ).status_code
        == 403
    )

    # Create and move claim to IN_REVIEW as human adjuster
    assert (
        client.post(
            "/api/v1/claims", json={"claim_id": "CLM-ROLE-1"}, headers=adjuster_headers
        ).status_code
        == 201
    )
    assert (
        client.post(
            "/api/v1/claims/CLM-ROLE-1/documents",
            files={"file": ("note.txt", b"Policy Number: POL-ROLE-1", "text/plain")},
            headers=ai_service_headers,
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/v1/claims/CLM-ROLE-1/submit-review", headers=ai_service_headers
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/v1/claims/CLM-ROLE-1/submit-review", headers=adjuster_headers
        ).status_code
        == 200
    )

    # AI service cannot perform ANY review decision type (even on missing claims)
    for decision_type in ("APPROVE", "REJECT", "REQUEST_INFO", "AUTHORIZE_PAYMENT"):
        r_ai = client.post(
            "/api/v1/claims/CLM-ROLE-1/review",
            json={"decision": decision_type, "rationale": "Automated attempt"},
            headers=ai_service_headers,
        )
        assert r_ai.status_code == 403
        assert r_ai.json()["error_code"] == "AUTONOMOUS_DECISION_PROHIBITED"

    r_ai_missing = client.post(
        "/api/v1/claims/CLM-DOES-NOT-EXIST/review",
        json={"decision": "APPROVE", "rationale": "Automated probe"},
        headers=ai_service_headers,
    )
    assert r_ai_missing.status_code == 403
    assert r_ai_missing.json()["error_code"] == "AUTONOMOUS_DECISION_PROHIBITED"

    # Human adjuster still cannot approve or reject claims
    r_adj = client.post(
        "/api/v1/claims/CLM-ROLE-1/review",
        json={"decision": "REJECT", "rationale": "Adjuster out of authority"},
        headers=adjuster_headers,
    )
    assert r_adj.status_code == 403
    assert r_adj.json()["error_code"] == "UNAUTHORIZED_DECISION"

    # Human admin (or reviewer) is permitted to record final decision
    r_admin = client.post(
        "/api/v1/claims/CLM-ROLE-1/review",
        json={
            "decision": "APPROVE",
            "rationale": "Verified all documents and coverage rules.",
        },
        headers=admin_headers,
    )
    assert r_admin.status_code == 200
    assert r_admin.json()["claim"]["status"] == "APPROVED"
    assert r_admin.json()["review_decision"]["actor_type"] == "HUMAN"
    assert r_admin.json()["review_decision"]["actor_role"] == "admin"

    # Audit trail records blocked attempts and final human approval
    audit = client.get("/api/v1/claims/CLM-ROLE-1/audit", headers=reviewer_headers).json()
    blocked_events = [e for e in audit["events"] if e["event_type"] == "REVIEW_DECISION_BLOCKED"]
    assert len(blocked_events) >= 5
