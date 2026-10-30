package dev.example.rag.application.port;
import dev.example.rag.domain.Chunk;
import dev.example.rag.domain.Match;
import java.util.List;
public interface VectorStorePort {
  void upsert(List<Chunk> chunks, List<List<Double>> vectors);
  List<Match> search(List<Double> vector, int limit);
}
