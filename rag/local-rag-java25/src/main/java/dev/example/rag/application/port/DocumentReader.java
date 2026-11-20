package dev.example.rag.application.port;
public interface DocumentReader {
  String readPdfBytes(byte[] bytes);
}
