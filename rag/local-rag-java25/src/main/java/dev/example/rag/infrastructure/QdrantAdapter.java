package dev.example.rag.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.example.rag.application.port.UpstreamUnavailableException;
import dev.example.rag.application.port.VectorStorePort;
import dev.example.rag.domain.Chunk;
import dev.example.rag.domain.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class QdrantAdapter implements VectorStorePort {
    private final RestClient client;
    private final RagProperties properties;
    private volatile boolean collectionReady;

    public QdrantAdapter(@Qualifier("qdrantClient") RestClient client, RagProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void upsert(List<Chunk> chunks, List<List<Double>> vectors) {
        if (chunks.isEmpty() || chunks.size() != vectors.size()) {
            throw new IllegalStateException("Invalid vector batch");
        }
        int dimensions = vectors.getFirst().size();
        if (dimensions == 0 || vectors.stream().anyMatch(v -> v.size() != dimensions)) {
            throw new IllegalStateException("Embedding dimension mismatch");
        }
        try {
            ensureCollection(dimensions);
            for (int from = 0; from < chunks.size(); from += properties.upsertBatchSize()) {
                int to = Math.min(from + properties.upsertBatchSize(), chunks.size());
                client.put().uri("/collections/{name}/points?wait=true", properties.collection())
                        .body(Map.of("points", points(chunks.subList(from, to), vectors.subList(from, to))))
                        .retrieve().toBodilessEntity();
            }
        } catch (HttpClientErrorException.NotFound e) {
            collectionReady = false;
            throw new UpstreamUnavailableException("Qdrant collection disappeared; retry the upload", e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Qdrant upsert failed", e);
        }
    }

    @Override
    public List<Match> search(List<Double> vector, int limit) {
        QueryResponse response;
        try {
            response = client.post().uri("/collections/{name}/points/query", properties.collection())
                    .body(new QueryRequest(vector, limit, true))
                    .retrieve().body(QueryResponse.class);
        } catch (HttpClientErrorException.NotFound missingCollection) {
            return List.of();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Qdrant search failed", e);
        }
        if (response == null || response.result() == null || response.result().points() == null) {
            throw new UpstreamUnavailableException("Unexpected Qdrant search response", null);
        }
        return response.result().points().stream()
                .filter(point -> point.payload() != null)
                .map(point -> new Match(point.payload().documentId(), point.payload().documentName(),
                        point.payload().text(), point.score()))
                .toList();
    }

    private void ensureCollection(int dimension) {
        if (collectionReady) {
            return;
        }
        try {
            client.get().uri("/collections/{name}", properties.collection()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound missing) {
            try {
                client.put().uri("/collections/{name}", properties.collection())
                        .body(Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")))
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException.Conflict alreadyCreated) {
                // Created concurrently by another request.
            }
        }
        collectionReady = true;
    }

    private static List<Map<String, Object>> points(List<Chunk> chunks, List<List<Double>> vectors) {
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            points.add(Map.of("id", chunk.id(), "vector", vectors.get(i), "payload", Map.of(
                    "documentId", chunk.documentId(), "documentName", chunk.documentName(),
                    "text", chunk.text(), "ordinal", chunk.ordinal())));
        }
        return points;
    }

    record QueryRequest(List<Double> query, int limit, @JsonProperty("with_payload") boolean withPayload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResponse(Result result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<Point> points) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Point(double score, Payload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(String documentId, String documentName, String text) {}
}
