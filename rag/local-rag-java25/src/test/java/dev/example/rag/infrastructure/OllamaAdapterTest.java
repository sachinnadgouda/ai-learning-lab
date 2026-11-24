package dev.example.rag.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.example.rag.application.port.UpstreamUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaAdapterTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OllamaAdapter adapter = new OllamaAdapter(builder.build(), TestProperties.defaults());

    @Test
    void embedAllSplitsRequestsIntoConfiguredBatches() {
        server.expect(requestTo("http://ollama/api/embed"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("{\"embeddings\":[[0.1],[0.2]],\"model\":\"x\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ollama/api/embed"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess("{\"embeddings\":[[0.3]]}", MediaType.APPLICATION_JSON));

        var vectors = adapter.embedAll(List.of("a", "b", "c"));

        assertThat(vectors).containsExactly(List.of(0.1), List.of(0.2), List.of(0.3));
        server.verify();
    }

    @Test
    void embedAllRejectsMismatchedResponse() {
        server.expect(requestTo("http://ollama/api/embed"))
                .andRespond(withSuccess("{\"embeddings\":[[0.1]]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.embedAll(List.of("a", "b")))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void answerSendsConfiguredOptionsAndReturnsContent() {
        server.expect(requestTo("http://ollama/api/chat"))
                .andExpect(jsonPath("$.model").value("chat-model"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.options.num_predict").value(256))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REFERENCE TEXT")))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"25 days\"},\"done\":true}",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.answer("Leave?", "[Source 1] 25 days")).isEqualTo("25 days");
    }

    @Test
    void serverErrorsBecomeUpstreamFailures() {
        server.expect(requestTo("http://ollama/api/chat")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.answerWithoutRag("Leave?"))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void emptyChatResponseIsAnUpstreamFailure() {
        server.expect(requestTo("http://ollama/api/chat"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.answerWithoutRag("Leave?"))
                .isInstanceOf(UpstreamUnavailableException.class);
    }
}
