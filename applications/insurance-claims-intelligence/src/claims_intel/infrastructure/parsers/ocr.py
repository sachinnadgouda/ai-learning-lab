"""Pluggable OCR adapters (Tesseract CLI via stdin/stdout, no temp files)."""

from __future__ import annotations

import shutil
import subprocess

from claims_intel.domain.errors import OcrProcessingError, OcrUnavailableError


class TesseractOcrAdapter:
    """Runs local Tesseract OCR over stdin/stdout when the binary is installed."""

    def __init__(self, *, tesseract_cmd: str = "tesseract", timeout_seconds: float = 15.0) -> None:
        self._tesseract_cmd = tesseract_cmd
        self._timeout_seconds = timeout_seconds

    def is_available(self) -> bool:
        return shutil.which(self._tesseract_cmd) is not None

    def extract_text(self, image_bytes: bytes, *, filename: str) -> str:
        binary = shutil.which(self._tesseract_cmd)
        if binary is None:
            raise OcrUnavailableError(
                f"Tesseract binary '{self._tesseract_cmd}' is not installed in this environment."
            )

        try:
            proc = subprocess.run(
                [binary, "stdin", "stdout", "--psm", "6"],
                input=image_bytes,
                capture_output=True,
                timeout=self._timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise OcrProcessingError(
                f"OCR timed out after {self._timeout_seconds}s for '{filename}'."
            ) from exc
        except OSError as exc:
            raise OcrUnavailableError(f"Unable to launch OCR binary: {exc}") from exc

        if proc.returncode != 0:
            stderr_msg = proc.stderr.decode("utf-8", errors="replace").strip()[:200]
            raise OcrProcessingError(
                f"OCR failed for '{filename}' (exit={proc.returncode}): {stderr_msg}"
            )

        return proc.stdout.decode("utf-8", errors="replace").strip()
