package dev.example.rag.infrastructure;

import dev.example.rag.application.port.DocumentReader;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PdfReaderAdapter implements DocumentReader {
    private final int maxBytes;

    public PdfReaderAdapter(@Value("${rag.max-pdf-bytes}") int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public String readPdfBytes(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("PDF file is empty");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("PDF exceeds size limit");
        }
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Upload a valid, unencrypted PDF.", ex);
        }
    }
}
