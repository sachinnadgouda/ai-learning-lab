package dev.example.rag.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.example.rag.application.port.UpstreamUnavailableException;
import dev.example.rag.domain.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QdrantAdapterTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final QdrantAdapter adapter = new QdrantAdapter(builder.build(), TestProperties.defaults());

    @Test
    void upsertCreatesMissingCollectionOnceAndBatchesPoints() {
        server.expect(once(), requestTo("http://qdrant/collections/test-collection"))
                .andExpect(method(GET)).andRespond(withResourceNotFound());
        server.expect(once(), requestTo("http://qdrant/collections/test-collection"))
                .andExpect(method(PUT)).andExpect(jsonPath("$.vectors.size").value(2))
                .andRespond(withSuccess());
        server.expect(requestTo("http://qdrant/collections/test-collection/points?wait=true"))
                .andExpect(method(PUT)).andExpect(jsonPath("$.points.length()").value(2))
                .andRespond(withSuccess());
        server.expect(requestTo("http://qdrant/collections/test-collection/points?wait=true"))
                .andExpect(method(PUT)).andExpect(jsonPath("$.points.length()").value(1))
                .andRespond(withSuccess());

        adapter.upsert(chunks(3), vectors(3, 2));

        server.verify();
    }

    @Test
    void upsertRejectsDimensionMismatch() {
        var mismatched = List.of(List.of(0.1, 0.2), List.of(0.1));

        assertThatThrownBy(() -> adapter.upsert(chunks(2), mismatched)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void upsertFailuresBecomeUpstreamFailures() {
        server.expect(requestTo("http://qdrant/collections/test-collection")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.upsert(chunks(1), vectors(1, 2)))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void searchMapsPointsToMatches() {
        server.expect(requestTo("http://qdrant/collections/test-collection/points/query"))
                .andExpect(method(POST)).andExpect(jsonPath("$.with_payload").value(true))
                .andRespond(withSuccess("""
                        {"result":{"points":[
                          {"id":"1","score":0.91,"payload":{"documentId":"d","documentName":"leave.pdf","text":"25 days","ordinal":0}},
                          {"id":"2","score":0.4}
                        ]},"status":"ok"}""", MediaType.APPLICATION_JSON));

        var matches = adapter.search(List.of(0.1, 0.2), 5);

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().documentName()).isEqualTo("leave.pdf");
        assertThat(matches.getFirst().score()).isEqualTo(0.91);
    }

    @Test
    void searchReturnsNothingWhenCollectionDoesNotExist() {
        server.expect(requestTo("http://qdrant/collections/test-collection/points/query"))
                .andRespond(withResourceNotFound());

        assertThat(adapter.search(List.of(0.1), 5)).isEmpty();
    }

    @Test
    void malformedSearchResponseIsAnUpstreamFailure() {
        server.expect(requestTo("http://qdrant/collections/test-collection/points/query"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.search(List.of(0.1), 5)).isInstanceOf(UpstreamUnavailableException.class);
    }

    private static List<Chunk> chunks(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Chunk("id-" + i, "doc", "leave.pdf", "text " + i, i)).toList();
    }

    private static List<List<Double>> vectors(int count, int dimensions) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> java.util.Collections.nCopies(dimensions, 0.5)).toList();
    }
}
