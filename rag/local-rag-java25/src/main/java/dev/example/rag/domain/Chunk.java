package dev.example.rag.domain;
public record Chunk(String id, String documentId, String text, int ordinal) {
  public Chunk { if (id == null || documentId == null || text == null || text.isBlank() || ordinal < 0) throw new IllegalArgumentException("Invalid chunk"); }
}
