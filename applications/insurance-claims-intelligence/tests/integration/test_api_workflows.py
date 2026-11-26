"""End-to-end FastAPI integration tests for uploads, RBAC, Q&A, governance, and errors."""

from __future__ import annotations

import io
import json

from fastapi.testclient import TestClient
from PIL import Image, PngImagePlugin

from claims_intel.infrastructure.parsers.pdf_builder import build_simple_pdf


def _png_bytes(description: str | None = None) -> bytes:
    img = Image.new("RGB", (60, 40), color=(100, 140, 180))
    buf = io.BytesIO()
    if description:
        meta = PngImagePlugin.PngInfo()
        meta.add_text("Description", description)
        img.save(buf, format="PNG", pnginfo=meta)
    else:
        img.save(buf, format="PNG")
    return buf.getvalue()


def _jpeg_bytes() -> bytes:
    img = Image.new("RGB", (60, 40), color=(210, 90, 70))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_operational_endpoints_and_correlation_id(client: TestClient) -> None:
    health = client.get("/health", headers={"X-Correlation-ID": "corr-health-1"})
    assert health.status_code == 200
    assert health.headers["X-Correlation-ID"] == "corr-health-1"
    assert health.json()["status"] == "ok"

    ready = client.get("/ready")
    assert ready.status_code == 200
    assert ready.json()["database_ready"] is True

    metrics = client.get("/metrics")
    assert metrics.status_code == 200
    assert "claims_http_requests_total" in metrics.text


def test_auth_401_and_rbac_403_enforcement(
    client: TestClient,
    adjuster_headers: dict[str, str],
    reviewer_headers: dict[str, str],
    ai_service_headers: dict[str, str],
) -> None:
    # Missing API key -> 401 RFC 9457
    r_unauth = client.get("/api/v1/claims")
    assert r_unauth.status_code == 401
    assert r_unauth.headers["content-type"].startswith("application/problem+json")
    assert r_unauth.json()["error_code"] == "UNAUTHORIZED"

    # Invalid API key -> 401
    r_bad = client.get("/api/v1/claims", headers={"X-API-Key": "wrong-key"})
    assert r_bad.status_code == 401

    # Create claim & submit for review as adjuster
    created = client.post(
        "/api/v1/claims",
        json={"claim_id": "CLM-RBAC-1", "policy_number": "POL-2026-1001"},
        headers=adjuster_headers,
    )
    assert created.status_code == 201
    sub = client.post("/api/v1/claims/CLM-RBAC-1/submit-review", headers=adjuster_headers)
    assert sub.status_code == 200
    assert sub.json()["status"] == "IN_REVIEW"

    # Adjuster attempting to approve -> 403 UNAUTHORIZED_DECISION
    r_adj = client.post(
        "/api/v1/claims/CLM-RBAC-1/review",
        json={"decision": "APPROVE", "rationale": "Adjuster trying to approve."},
        headers=adjuster_headers,
    )
    assert r_adj.status_code == 403
    assert r_adj.json()["error_code"] == "UNAUTHORIZED_DECISION"

    # AI Service attempting to approve -> 403 AUTONOMOUS_DECISION_PROHIBITED + audited!
    r_ai = client.post(
        "/api/v1/claims/CLM-RBAC-1/review",
        json={"decision": "APPROVE", "rationale": "AI attempting autonomous approval."},
        headers=ai_service_headers,
    )
    assert r_ai.status_code == 403
    assert r_ai.json()["error_code"] == "AUTONOMOUS_DECISION_PROHIBITED"

    # Verify blocked AI decision was logged in audit trail
    audit = client.get("/api/v1/claims/CLM-RBAC-1/audit", headers=reviewer_headers)
    assert audit.status_code == 200
    event_types = [e["event_type"] for e in audit.json()["events"]]
    assert "REVIEW_DECISION_BLOCKED" in event_types

    # Human reviewer approves cleanly -> 200 APPROVED
    r_rev = client.post(
        "/api/v1/claims/CLM-RBAC-1/review",
        json={
            "decision": "APPROVE",
            "rationale": "Verified invoices and policy schedule manually.",
            "supervisor_note": "Within delegated review authority.",
        },
        headers=reviewer_headers,
    )
    assert r_rev.status_code == 200
    assert r_rev.json()["claim"]["status"] == "APPROVED"


def test_multiformat_uploads_validation_errors_ocr_fallback_and_qa_workflow(
    client: TestClient,
    adjuster_headers: dict[str, str],
    reviewer_headers: dict[str, str],
) -> None:
    res = client.post(
        "/api/v1/claims",
        json={"claim_id": "CLM-FULL-1"},
        headers=adjuster_headers,
    )
    assert res.status_code == 201

    # 1. Valid PDF upload
    pdf_bytes = build_simple_pdf(
        [
            [
                "FIRST NOTICE OF LOSS",
                "Policy Number: POL-AUTO-2026-5500",
                "Claimant Name: Elena Vance",
                "Incident Date: 2026-06-15",
                "Incident Type: Hail Windshield Damage",
                "Claimed Amount: $2,400.00",
            ]
        ]
    )
    up_pdf = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("claim_form.pdf", pdf_bytes, "application/pdf")},
        headers=adjuster_headers,
    )
    assert up_pdf.status_code == 201
    assert up_pdf.json()["document"]["document_type"] == "CLAIM_FORM"
    assert up_pdf.json()["chunk_count"] >= 1

    # Re-upload identical PDF -> idempotent deduplication
    up_dup = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("claim_form.pdf", pdf_bytes, "application/pdf")},
        headers=adjuster_headers,
    )
    assert up_dup.status_code == 201
    assert up_dup.json()["deduplicated"] is True

    # 2. Valid TXT policy schedule
    policy_txt = (
        b"POLICY SCHEDULE\n"
        b"Policy Number: POL-AUTO-2026-5500\n"
        b"Policyholder: Elena Vance\n"
        b"Effective Period: 2026-01-01 to 2026-12-31\n"
        b"Covered Perils: hail, windshield, collision\n"
        b"Exclusions: racing\n"
        b"Deductible: $500.00\n"
        b"Coverage Limit: $30,000.00\n"
    )
    up_txt = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("policy_schedule.txt", policy_txt, "text/plain")},
        headers=adjuster_headers,
    )
    assert up_txt.status_code == 201

    # 3. Valid JSON invoice
    inv_json = json.dumps(
        {
            "vendor": "Alpine Glass",
            "claimant_name": "Elena Vance",
            "policy_number": "POL-AUTO-2026-5500",
            "invoice_total": 2400.00,
        }
    ).encode("utf-8")
    up_json = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("invoice_alpine.json", inv_json, "application/json")},
        headers=adjuster_headers,
    )
    assert up_json.status_code == 201

    # 4. Valid CSV repair estimate
    est_csv = b"item,cost\nWindshield,1900\nCalibration,500\n"
    up_csv = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("repair_estimate.csv", est_csv, "text/csv")},
        headers=adjuster_headers,
    )
    assert up_csv.status_code == 201

    # 5. PNG with metadata + JPEG without metadata (graceful OCR unavailability)
    up_png = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("photo_damage.png", _png_bytes("Cracked front windshield"), "image/png")},
        headers=adjuster_headers,
    )
    assert up_png.status_code == 201
    assert up_png.json()["document"]["ocr_status"] == "METADATA_ONLY"
    assert up_png.json()["chunk_count"] == 1

    up_jpg = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("photo_side.jpg", _jpeg_bytes(), "image/jpeg")},
        headers=adjuster_headers,
    )
    assert up_jpg.status_code == 201
    assert up_jpg.json()["document"]["ocr_status"] == "UNAVAILABLE"

    # 6. Error handling: corrupt PDF -> 400, unsupported format -> 415, oversized -> 413
    bad_pdf = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("corrupt.pdf", b"%PDF-1.4\ncorrupt-payload", "application/pdf")},
        headers=adjuster_headers,
    )
    assert bad_pdf.status_code == 400
    assert bad_pdf.json()["error_code"] == "INVALID_DOCUMENT"

    bad_ext = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("payload.exe", b"MZ1234", "application/octet-stream")},
        headers=adjuster_headers,
    )
    assert bad_ext.status_code == 415
    assert bad_ext.json()["error_code"] == "UNSUPPORTED_DOCUMENT_FORMAT"

    oversized = client.post(
        "/api/v1/claims/CLM-FULL-1/documents",
        files={"file": ("huge.txt", b"A" * (65 * 1024), "text/plain")},
        headers=adjuster_headers,
    )
    assert oversized.status_code == 413
    assert oversized.json()["error_code"] == "PAYLOAD_TOO_LARGE"

    # 7. Analyze claim & check coverage
    analysis = client.post("/api/v1/claims/CLM-FULL-1/analyze", headers=adjuster_headers)
    assert analysis.status_code == 200
    body = analysis.json()
    assert body["claim"]["status"] == "EXTRACTED"
    assert body["coverage"]["verdict"] == "COVERED"
    assert body["risk_assessment"]["severity"] == "LOW"
    assert body["risk_assessment"]["advisory_only"] is True

    cov = client.get("/api/v1/claims/CLM-FULL-1/coverage", headers=reviewer_headers)
    assert cov.status_code == 200
    assert cov.json()["verdict"] == "COVERED"

    # 8. Hybrid search & grounded Q&A
    search = client.post(
        "/api/v1/claims/CLM-FULL-1/search",
        json={"query": "deductible coverage limit POL-AUTO-2026-5500", "top_k": 3},
        headers=reviewer_headers,
    )
    assert search.status_code == 200
    assert len(search.json()["hits"]) >= 1

    qa_ok = client.post(
        "/api/v1/claims/CLM-FULL-1/qa",
        json={"question": "What is the deductible for policy POL-AUTO-2026-5500?"},
        headers=reviewer_headers,
    )
    assert qa_ok.status_code == 200
    assert qa_ok.json()["status"] == "ANSWERED"
    assert "500" in qa_ok.json()["answer"]
    assert len(qa_ok.json()["citations"]) >= 1

    # Unanswerable Q&A -> abstains cleanly
    qa_abs = client.post(
        "/api/v1/claims/CLM-FULL-1/qa",
        json={"question": "What was the airline flight number and luggage claim receipt?"},
        headers=reviewer_headers,
    )
    assert qa_abs.status_code == 200
    assert qa_abs.json()["status"] == "UNANSWERABLE"
    assert qa_abs.json()["abstained"] is True
    assert qa_abs.json()["citations"] == []
