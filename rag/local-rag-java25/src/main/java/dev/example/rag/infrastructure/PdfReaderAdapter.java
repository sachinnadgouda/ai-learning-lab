package dev.example.rag.infrastructure;
import dev.example.rag.application.port.DocumentReader;
import java.io.*;
import java.net.*;
import java.time.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class PdfReaderAdapter implements DocumentReader {
  private final int maxBytes;
  public PdfReaderAdapter(@Value("${rag.max-pdf-bytes}") int maxBytes) {this.maxBytes=maxBytes;}
  @Override public String readPdf(String url) {
    try {
      URI uri=URI.create(url);
      // Intentionally restrict remote downloads to the tutorial's fixed public PDF host.
      if(!"https".equals(uri.getScheme()) || !"phi-public.s3.amazonaws.com".equals(uri.getHost()) || !"/recipes/ThaiRecipes.pdf".equals(uri.getPath()) || uri.getRawQuery()!=null || uri.getRawUserInfo()!=null) throw new IllegalArgumentException("Only the configured tutorial PDF URL is permitted");
      HttpURLConnection conn=(HttpURLConnection)uri.toURL().openConnection();
      conn.setConnectTimeout((int)Duration.ofSeconds(5).toMillis());conn.setReadTimeout((int)Duration.ofSeconds(30).toMillis());conn.setInstanceFollowRedirects(false);
      try {
        if(conn.getResponseCode()!=200) throw new IOException("PDF download failed: HTTP "+conn.getResponseCode());
        try(InputStream stream=conn.getInputStream()) {
          byte[] bytes=stream.readNBytes(maxBytes+1);
          if(bytes.length>maxBytes) throw new IllegalArgumentException("PDF exceeds size limit");
          return readPdfBytes(bytes);
        }
      } finally {conn.disconnect();}
    } catch(IOException ex) {throw new IllegalStateException("Unable to load PDF",ex);}
  }
  @Override public String readPdfBytes(byte[] bytes) {
    try {
      if(bytes.length==0) throw new IllegalArgumentException("PDF file is empty");
      if(bytes.length>maxBytes) throw new IllegalArgumentException("PDF exceeds size limit");
      try(var document=Loader.loadPDF(bytes)) {return new PDFTextStripper().getText(document);}
    } catch(IOException ex) {throw new IllegalStateException("Unable to read uploaded PDF",ex);}
  }
}
