"""Minimal deterministic PDF 1.4 generator for synthetic sample claims and integration tests."""

from __future__ import annotations

from collections.abc import Sequence


def _escape_pdf_text(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def build_simple_pdf(pages_lines: Sequence[Sequence[str]]) -> bytes:
    """Create a valid multi-page PDF 1.4 document with Helvetica text extractable by pypdf."""
    if not pages_lines:
        pages_lines = [["Empty page"]]

    objects: list[bytes] = []

    # Object 1: Catalog
    # Object 2: Pages
    # Object 3: Font (Helvetica)
    # Objects 4..: Pairs of (Page, Content stream)
    num_pages = len(pages_lines)
    page_obj_ids = [4 + i * 2 for i in range(num_pages)]

    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{pid} 0 R" for pid in page_obj_ids)
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {num_pages} >>".encode("ascii"))
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    for idx, lines in enumerate(pages_lines):
        page_id = 4 + idx * 2
        content_id = page_id + 1
        stream_cmds: list[str] = ["BT", "/F1 11 Tf", "50 760 Td", "14 TL"]
        for line_idx, line in enumerate(lines):
            escaped = _escape_pdf_text(line)
            if line_idx == 0:
                stream_cmds.append(f"({escaped}) Tj")
            else:
                stream_cmds.append(f"T* ({escaped}) Tj")
        stream_cmds.append("ET")
        stream_bytes = "\n".join(stream_cmds).encode("latin-1", errors="replace")

        page_dict = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>"
        ).encode("ascii")
        content_obj = (
            f"<< /Length {len(stream_bytes)} >>\nstream\n".encode("ascii")
            + stream_bytes
            + b"\nendstream"
        )
        objects.append(page_dict)
        objects.append(content_obj)

    buf = bytearray(b"%PDF-1.4\n")
    offsets: list[int] = [0]
    for obj_num, body in enumerate(objects, start=1):
        offsets.append(len(buf))
        buf.extend(f"{obj_num} 0 obj\n".encode("ascii"))
        buf.extend(body)
        buf.extend(b"\nendobj\n")

    xref_pos = len(buf)
    buf.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    buf.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        buf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))

    buf.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_pos}\n%%EOF\n"
        ).encode("ascii")
    )
    return bytes(buf)
