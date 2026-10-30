package dev.example.rag.application.port;
public interface DocumentReader {
  String readPdf(String url);
  String readPdfBytes(byte[] bytes);
}
