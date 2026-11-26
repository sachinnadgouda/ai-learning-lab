"""Safe multi-format document parser with magic-byte verification and graceful OCR handling."""

from __future__ import annotations

import csv
import io
import json
from pathlib import PurePosixPath
from typing import Any

from PIL import Image, UnidentifiedImageError
from pypdf import PdfReader
from pypdf.errors import PdfReadError

from claims_intel.application.ports import OcrPort, ParsedDocument
from claims_intel.domain.chunking import ExtractedPage
from claims_intel.domain.errors import (
    InvalidDocumentError,
    OcrProcessingError,
    OcrUnavailableError,
    UnsupportedDocumentFormatError,
)
from claims_intel.domain.models import OcrStatus

_PDF_MAGIC = b"%PDF-"
_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
_JPEG_MAGIC = b"\xff\xd8\xff"
_DISALLOWED_TEXT_SIGNATURES: tuple[bytes, ...] = (
    _PDF_MAGIC,
    _PNG_MAGIC,
    _JPEG_MAGIC,
    b"PK\x03\x04",
    b"MZ",
    b"\x7fELF",
    b"GIF87a",
    b"GIF89a",
)

_SUPPORTED_EXTENSIONS: dict[str, str] = {
    ".pdf": "application/pdf",
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".json": "application/json",
    ".csv": "text/csv",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
}


class MultiFormatDocumentParser:
    """Validates file signatures and extracts page-level text from PDF, text, JSON, CSV, images."""

    def __init__(self, *, ocr_adapter: OcrPort) -> None:
        self._ocr = ocr_adapter

    def parse(
        self,
        *,
        filename: str,
        content: bytes,
        declared_mime_type: str | None = None,
    ) -> ParsedDocument:
        if not content:
            raise InvalidDocumentError(f"Uploaded file '{filename}' is empty.")

        ext = PurePosixPath(filename).suffix.lower()
        if ext not in _SUPPORTED_EXTENSIONS:
            raise UnsupportedDocumentFormatError(
                f"Unsupported document extension '{ext or '<none>'}'. "
                f"Supported formats: {', '.join(sorted(_SUPPORTED_EXTENSIONS))}."
            )

        canonical_mime = _SUPPORTED_EXTENSIONS[ext]

        if ext == ".pdf":
            return self._parse_pdf(filename, content, canonical_mime)
        if ext in {".png", ".jpg", ".jpeg"}:
            return self._parse_image(filename, content, ext, canonical_mime)
        if ext == ".json":
            return self._parse_json(filename, content, canonical_mime)
        if ext == ".csv":
            return self._parse_csv(filename, content, canonical_mime)
        return self._parse_plain_text(filename, content, canonical_mime)

    def _parse_pdf(self, filename: str, content: bytes, mime_type: str) -> ParsedDocument:
        if not content.startswith(_PDF_MAGIC):
            raise InvalidDocumentError(
                f"File '{filename}' failed PDF magic-byte verification (expected %PDF- header)."
            )
        try:
            reader = PdfReader(io.BytesIO(content), strict=False)
            if reader.is_encrypted:
                raise InvalidDocumentError(f"Encrypted PDF '{filename}' is not supported.")
            pages: list[ExtractedPage] = []
            for idx, page in enumerate(reader.pages, start=1):
                extracted = (page.extract_text() or "").strip()
                if extracted:
                    pages.append(ExtractedPage(page_number=idx, text=extracted))
        except InvalidDocumentError:
            raise
        except (PdfReadError, Exception) as exc:
            raise InvalidDocumentError(
                f"Malformed or corrupt PDF '{filename}': {type(exc).__name__}"
            ) from exc

        if not pages:
            raise InvalidDocumentError(
                f"PDF '{filename}' contains no extractable text pages."
            )
        return ParsedDocument(
            mime_type=mime_type,
            pages=pages,
            ocr_status=OcrStatus.NOT_REQUIRED,
        )

    def _parse_image(
        self,
        filename: str,
        content: bytes,
        ext: str,
        mime_type: str,
    ) -> ParsedDocument:
        if ext == ".png" and not content.startswith(_PNG_MAGIC):
            raise InvalidDocumentError(
                f"File '{filename}' failed PNG magic-byte verification."
            )
        if ext in {".jpg", ".jpeg"} and not content.startswith(_JPEG_MAGIC):
            raise InvalidDocumentError(
                f"File '{filename}' failed JPEG magic-byte verification."
            )

        metadata_lines: list[str] = []
        try:
            with Image.open(io.BytesIO(content)) as img_verify:
                img_verify.verify()
            with Image.open(io.BytesIO(content)) as img:
                width, height = img.size
                img_format = img.format or ext.lstrip(".").upper()
                metadata_lines.append(
                    f"Image Evidence: {filename} ({img_format} {width}x{height})"
                )
                # Extract PNG tEXt / EXIF embedded metadata captions/receipt text safely
                for key in ("Description", "Comment", "ReceiptText", "Title", "Caption"):
                    val = img.info.get(key)
                    if isinstance(val, str) and val.strip():
                        metadata_lines.append(f"{key}: {val.strip()}")
                exif = img.getexif()
                if exif:
                    # 270 = ImageDescription, 37510 = UserComment
                    for tag_id, label in ((270, "ImageDescription"), (37510, "UserComment")):
                        raw_exif = exif.get(tag_id)
                        if isinstance(raw_exif, str) and raw_exif.strip():
                            metadata_lines.append(f"{label}: {raw_exif.strip()}")
        except (UnidentifiedImageError, OSError, ValueError) as exc:
            raise InvalidDocumentError(
                f"Corrupt or unreadable image '{filename}': {type(exc).__name__}"
            ) from exc

        has_embedded_text = len(metadata_lines) > 1
        ocr_text = ""
        ocr_status = OcrStatus.COMPLETED
        ocr_error: str | None = None

        try:
            ocr_text = self._ocr.extract_text(content, filename=filename)
        except OcrUnavailableError as exc:
            ocr_error = exc.message
            ocr_status = OcrStatus.METADATA_ONLY if has_embedded_text else OcrStatus.UNAVAILABLE
        except OcrProcessingError as exc:
            ocr_error = exc.message
            ocr_status = OcrStatus.METADATA_ONLY if has_embedded_text else OcrStatus.FAILED

        text_parts: list[str] = []
        if has_embedded_text:
            text_parts.extend(metadata_lines)
        if ocr_text:
            text_parts.append(ocr_text)

        pages: list[ExtractedPage] = []
        if text_parts:
            pages.append(ExtractedPage(page_number=1, text="\n".join(text_parts)))

        return ParsedDocument(
            mime_type=mime_type,
            pages=pages,
            ocr_status=ocr_status,
            ocr_error=ocr_error,
        )

    def _decode_utf8_text(self, filename: str, content: bytes) -> str:
        for sig in _DISALLOWED_TEXT_SIGNATURES:
            if content.startswith(sig):
                raise InvalidDocumentError(
                    f"File '{filename}' contains unexpected binary file signature."
                )
        if b"\x00" in content:
            raise InvalidDocumentError(f"Text file '{filename}' contains binary null bytes.")
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise InvalidDocumentError(
                f"File '{filename}' is not valid UTF-8 text."
            ) from exc
        if not text.strip():
            raise InvalidDocumentError(f"File '{filename}' contains no text content.")
        return text

    def _parse_plain_text(self, filename: str, content: bytes, mime_type: str) -> ParsedDocument:
        text = self._decode_utf8_text(filename, content)
        # Support optional explicit form-feed page breaks in text documents
        raw_pages = [p.strip() for p in text.split("\f") if p.strip()]
        pages = [ExtractedPage(page_number=i, text=p) for i, p in enumerate(raw_pages, start=1)]
        return ParsedDocument(mime_type=mime_type, pages=pages, ocr_status=OcrStatus.NOT_REQUIRED)

    def _parse_json(self, filename: str, content: bytes, mime_type: str) -> ParsedDocument:
        raw_text = self._decode_utf8_text(filename, content)
        try:
            parsed: Any = json.loads(raw_text)
        except json.JSONDecodeError as exc:
            raise InvalidDocumentError(f"Malformed JSON file '{filename}': {exc.msg}") from exc

        lines = _flatten_json_to_lines(parsed)
        normalized_text = "\n".join(lines).strip() or json.dumps(parsed, indent=2)
        return ParsedDocument(
            mime_type=mime_type,
            pages=[ExtractedPage(page_number=1, text=normalized_text)],
            ocr_status=OcrStatus.NOT_REQUIRED,
        )

    def _parse_csv(self, filename: str, content: bytes, mime_type: str) -> ParsedDocument:
        raw_text = self._decode_utf8_text(filename, content)
        reader = csv.reader(io.StringIO(raw_text))
        rows = [row for row in reader if any(cell.strip() for cell in row)]
        if not rows:
            raise InvalidDocumentError(f"CSV file '{filename}' contains no data rows.")

        headers = [h.strip() for h in rows[0]]
        formatted_lines: list[str] = []
        if len(rows) == 1:
            formatted_lines.append(", ".join(headers))
        else:
            for row_idx, row in enumerate(rows[1:], start=1):
                pairs = [
                    f"{headers[i] if i < len(headers) else f'col_{i}'}: {cell.strip()}"
                    for i, cell in enumerate(row)
                    if cell.strip()
                ]
                formatted_lines.append(f"Row {row_idx} | " + " | ".join(pairs))

        return ParsedDocument(
            mime_type=mime_type,
            pages=[ExtractedPage(page_number=1, text="\n".join(formatted_lines))],
            ocr_status=OcrStatus.NOT_REQUIRED,
        )


def _flatten_json_to_lines(value: Any, prefix: str = "") -> list[str]:
    lines: list[str] = []
    if isinstance(value, dict):
        for k, v in value.items():
            key_label = f"{prefix}.{k}" if prefix else str(k)
            if isinstance(v, (dict, list)):
                lines.extend(_flatten_json_to_lines(v, key_label))
            else:
                # Emit leaf keys cleanly so deterministic regex extractors match exact labels
                lines.append(f"{k}: {v}" if not prefix else f"{key_label}: {v}")
    elif isinstance(value, list):
        for idx, item in enumerate(value, start=1):
            if isinstance(item, dict):
                summary = ", ".join(f"{k}: {v}" for k, v in item.items())
                lines.append(f"{prefix}[{idx}]: {summary}")
            else:
                lines.append(f"{prefix}[{idx}]: {item}")
    else:
        lines.append(f"{prefix}: {value}" if prefix else str(value))
    return lines
