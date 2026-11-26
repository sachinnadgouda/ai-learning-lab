"""Page-aware chunker preserving exact 1-based page numbers and character offsets."""

from __future__ import annotations

from collections.abc import Sequence

from pydantic import BaseModel, ConfigDict, Field

from claims_intel.domain.models import DocumentType, EvidenceChunk


class ExtractedPage(BaseModel):
    model_config = ConfigDict(frozen=True)

    page_number: int = Field(ge=1)
    text: str


def chunk_pages(
    *,
    claim_id: str,
    document_id: str,
    filename: str,
    document_type: DocumentType,
    pages: Sequence[ExtractedPage],
    chunk_size_chars: int = 700,
    chunk_overlap_chars: int = 120,
) -> list[EvidenceChunk]:
    """Split extracted pages into overlapping chunks while preserving exact provenance.

    Offsets (`char_start`, `char_end`) are exact 0-based character indices within each page.
    Chunk IDs are content-addressed deterministically as `{document_id}:p{page}:c{ordinal}`.
    """
    if chunk_size_chars < 100:
        raise ValueError("chunk_size_chars must be >= 100")
    if chunk_overlap_chars < 0 or chunk_overlap_chars >= chunk_size_chars:
        raise ValueError("chunk_overlap_chars must be >= 0 and < chunk_size_chars")

    chunks: list[EvidenceChunk] = []
    ordinal = 0

    for page in pages:
        raw_text = page.text
        if not raw_text or not raw_text.strip():
            continue

        page_len = len(raw_text)
        cursor = 0

        while cursor < page_len:
            # Skip leading whitespace while preserving true offsets
            while cursor < page_len and raw_text[cursor].isspace():
                cursor += 1
            if cursor >= page_len:
                break

            window_end = min(cursor + chunk_size_chars, page_len)
            if window_end < page_len:
                # Prefer breaking at newline or sentence/word boundary within last 35% of window
                min_break = cursor + int(chunk_size_chars * 0.65)
                newline_pos = raw_text.rfind("\n", min_break, window_end)
                if newline_pos != -1:
                    window_end = newline_pos
                else:
                    space_pos = raw_text.rfind(" ", min_break, window_end)
                    if space_pos != -1:
                        window_end = space_pos

            trimmed_end = window_end
            while trimmed_end > cursor and raw_text[trimmed_end - 1].isspace():
                trimmed_end -= 1

            segment = raw_text[cursor:trimmed_end]
            if segment:
                chunk_id = f"{document_id}:p{page.page_number}:c{ordinal}"
                chunks.append(
                    EvidenceChunk(
                        chunk_id=chunk_id,
                        claim_id=claim_id,
                        document_id=document_id,
                        filename=filename,
                        document_type=document_type,
                        page_number=page.page_number,
                        char_start=cursor,
                        char_end=trimmed_end,
                        text=segment,
                    )
                )
                ordinal += 1

            if window_end >= page_len:
                break

            next_cursor = max(cursor + 1, window_end - chunk_overlap_chars)
            cursor = next_cursor

    return chunks
