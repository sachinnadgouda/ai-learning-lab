package dev.example.rag.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfReaderAdapterTest {
    private final PdfReaderAdapter reader = new PdfReaderAdapter(100_000);

    @Test
    void extractsTextFromPdf() throws IOException {
        assertThat(reader.readPdfBytes(pdfWith("Annual leave is 25 days."))).contains("Annual leave is 25 days.");
    }

    @Test
    void rejectsEmptyOversizedAndInvalidFiles() {
        assertThatThrownBy(() -> reader.readPdfBytes(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PdfReaderAdapter(4).readPdfBytes(new byte[5]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readPdfBytes("not a pdf".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid, unencrypted PDF");
    }

    private static byte[] pdfWith(String text) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
