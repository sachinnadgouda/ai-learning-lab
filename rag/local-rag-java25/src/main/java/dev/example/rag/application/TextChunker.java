package dev.example.rag.application;
import dev.example.rag.domain.Chunk;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
public final class TextChunker {
  private final int size, overlap;
  public TextChunker(int size, int overlap) { if (size < 1 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("Invalid chunk settings"); this.size=size;this.overlap=overlap; }
  public List<Chunk> split(String documentId, String text) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Document contains no text");
    String normalized=text.replaceAll("\\s+", " ").trim();
    List<Chunk> result=new ArrayList<>();
    for(int start=0, ordinal=0;start<normalized.length();ordinal++) {
      int end=Math.min(start+size,normalized.length());
      result.add(new Chunk(UUID.nameUUIDFromBytes((documentId+":"+ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),documentId,normalized.substring(start,end),ordinal));
      if(end==normalized.length()) break;
      start=end-overlap;
    }
    return List.copyOf(result);
  }
}
