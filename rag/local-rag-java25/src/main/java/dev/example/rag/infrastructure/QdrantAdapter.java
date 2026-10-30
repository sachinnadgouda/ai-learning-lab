package dev.example.rag.infrastructure;
import dev.example.rag.application.port.VectorStorePort;
import dev.example.rag.domain.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
@Component
public class QdrantAdapter implements VectorStorePort {
  private final RestClient client;private final String collection;
  public QdrantAdapter(@Qualifier("qdrantClient") RestClient client,@Value("${rag.collection}") String collection) {this.client=client;this.collection=collection;}
  private void ensureCollection(int dimension) {
    try {client.get().uri("/collections/{name}",collection).retrieve().toBodilessEntity();}
    catch(HttpClientErrorException.NotFound missing) {
      client.put().uri("/collections/{name}",collection).body(Map.of("vectors",Map.of("size",dimension,"distance","Cosine"))).retrieve().toBodilessEntity();
    }
  }
  @Override public void upsert(List<Chunk> chunks,List<List<Double>> vectors) {
    if(chunks.isEmpty() || chunks.size()!=vectors.size()) throw new IllegalArgumentException("Invalid vector batch");
    int dimensions=vectors.getFirst().size();
    if(dimensions==0 || vectors.stream().anyMatch(v->v.size()!=dimensions)) throw new IllegalArgumentException("Embedding dimension mismatch");
    ensureCollection(dimensions);
    List<Map<String,Object>> points=new ArrayList<>();
    for(int i=0;i<chunks.size();i++) {
      Chunk c=chunks.get(i);
      points.add(Map.of("id",c.id(),"vector",vectors.get(i),"payload",Map.of("documentId",c.documentId(),"text",c.text(),"ordinal",c.ordinal())));
    }
    client.put().uri("/collections/{name}/points?wait=true",collection).body(Map.of("points",points)).retrieve().toBodilessEntity();
  }
  @Override public List<Match> search(List<Double> vector,int limit) {
    Map<?,?> response=client.post().uri("/collections/{name}/points/query",collection).body(Map.of("query",vector,"limit",limit,"with_payload",true)).retrieve().body(Map.class);
    if(response==null || !(response.get("result") instanceof Map<?,?> result) || !(result.get("points") instanceof List<?> points)) throw new IllegalStateException("Unexpected Qdrant search response");
    List<Match> matches=new ArrayList<>();
    for(Object p:points) {
      Map<?,?> point=(Map<?,?>)p; Map<?,?> payload=(Map<?,?>)point.get("payload");
      matches.add(new Match((String)payload.get("documentId"),(String)payload.get("text"),((Number)point.get("score")).doubleValue()));
    }
    return List.copyOf(matches);
  }
}
