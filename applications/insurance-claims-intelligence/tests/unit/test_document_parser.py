"""Unit tests for multi-format document parser, magic-byte validation, and graceful OCR handling."""

from __future__ import annotations

import io

import pytest
from PIL import Image, PngImagePlugin

from claims_intel.domain.errors import (
    InvalidDocumentError,
    OcrProcessingError,
    OcrUnavailableError,
    UnsupportedDocumentFormatError,
)
from claims_intel.domain.models import OcrStatus
from claims_intel.infrastructure.parsers.document_parser import MultiFormatDocumentParser
from claims_intel.infrastructure.parsers.pdf_builder import build_simple_pdf


class StubSuccessOcr:
    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        return "OCR Extracted Receipt Total: $1,250.00"


class StubUnavailableOcr:
    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        raise OcrUnavailableError("tesseract binary missing")


class StubFailingOcr:
    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        raise OcrProcessingError("tesseract exited 1")


def _make_png(*, description: str | None = None) -> bytes:
    img = Image.new("RGB", (40, 30), color=(90, 120, 150))
    buf = io.BytesIO()
    if description:
        meta = PngImagePlugin.PngInfo()
        meta.add_text("Description", description)
        img.save(buf, format="PNG", pnginfo=meta)
    else:
        img.save(buf, format="PNG")
    return buf.getvalue()


def _make_jpeg() -> bytes:
    img = Image.new("RGB", (40, 30), color=(200, 100, 50))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_parse_valid_pdf_multipage() -> None:
    parser = MultiFormatDocumentParser(ocr_adapter=StubUnavailableOcr())
    pdf_bytes = build_simple_pdf([["Page One Claim Form"], ["Page Two Signature"]])
    parsed = parser.parse(filename="claim.pdf", content=pdf_bytes)
    assert parsed.mime_type == "application/pdf"
    assert len(parsed.pages) == 2
    assert "Page One Claim Form" in parsed.pages[0].text
    assert "Page Two Signature" in parsed.pages[1].text


def test_rejects_corrupt_or_spoofed_pdf() -> None:
    parser = MultiFormatDocumentParser(ocr_adapter=StubUnavailableOcr())
    with pytest.raises(InvalidDocumentError):
        parser.parse(filename="fake.pdf", content=b"not actually a pdf file")

    with pytest.raises(InvalidDocumentError):
        parser.parse(filename="corrupt.pdf", content=b"%PDF-1.4\ngarbage-corrupt-body")


def test_rejects_unsupported_extension_and_binary_polyglot_text() -> None:
    parser = MultiFormatDocumentParser(ocr_adapter=StubUnavailableOcr())
    with pytest.raises(UnsupportedDocumentFormatError):
        parser.parse(filename="malware.exe", content=b"MZ\x90\x00")

    with pytest.raises(InvalidDocumentError):
        parser.parse(filename="polyglot.txt", content=b"%PDF-1.4 disguised as text")


def test_parse_json_and_csv() -> None:
    parser = MultiFormatDocumentParser(ocr_adapter=StubUnavailableOcr())
    parsed_json = parser.parse(
        filename="invoice.json",
        content=b'{"vendor": "Apex Body", "invoice_total": 1500.00}',
    )
    assert "Apex Body" in parsed_json.pages[0].text

    parsed_csv = parser.parse(
        filename="items.csv",
        content=b"item,cost\nBumper,800\nPaint,700\n",
    )
    assert "Bumper" in parsed_csv.pages[0].text
    assert "Paint" in parsed_csv.pages[0].text


def test_image_ocr_success_and_graceful_degradation() -> None:
    png_with_meta = _make_png(description="Front bumper dent photo")
    png_plain = _make_png()
    jpeg_plain = _make_jpeg()

    # Success OCR
    ok_parser = MultiFormatDocumentParser(ocr_adapter=StubSuccessOcr())
    ok_res = ok_parser.parse(filename="receipt.png", content=png_with_meta)
    assert ok_res.ocr_status == OcrStatus.COMPLETED
    assert "OCR Extracted Receipt Total" in ok_res.pages[0].text
    assert "Front bumper dent photo" in ok_res.pages[0].text

    # Unavailable OCR with embedded PNG metadata -> METADATA_ONLY (does not crash)
    unavail_parser = MultiFormatDocumentParser(ocr_adapter=StubUnavailableOcr())
    meta_res = unavail_parser.parse(filename="photo.png", content=png_with_meta)
    assert meta_res.ocr_status == OcrStatus.METADATA_ONLY
    assert meta_res.ocr_error is not None
    assert len(meta_res.pages) == 1

    # Unavailable OCR without metadata -> UNAVAILABLE with 0 pages (does not crash)
    plain_res = unavail_parser.parse(filename="photo.jpg", content=jpeg_plain)
    assert plain_res.ocr_status == OcrStatus.UNAVAILABLE
    assert plain_res.pages == []

    # OCR runtime failure -> FAILED (does not crash)
    fail_parser = MultiFormatDocumentParser(ocr_adapter=StubFailingOcr())
    fail_res = fail_parser.parse(filename="damage.png", content=png_plain)
    assert fail_res.ocr_status == OcrStatus.FAILED
    assert "exited 1" in (fail_res.ocr_error or "")
