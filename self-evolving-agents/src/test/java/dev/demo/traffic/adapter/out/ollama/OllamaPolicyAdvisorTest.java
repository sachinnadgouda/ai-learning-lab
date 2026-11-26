package dev.demo.traffic.adapter.out.ollama;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.demo.traffic.application.model.AiPrompt;
import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real HTTP adapter against a local stub server standing in for Ollama. */
class OllamaPolicyAdvisorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private OllamaPolicyAdvisor start(int status, String body, long delayMs, Duration timeout, int retries) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            chatCalls.incrementAndGet();
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); } catch (java.io.IOException ignored) { }
        });
        server.createContext("/api/tags", ex -> {
            byte[] out = "{\"models\":[{\"name\":\"qwen2.5:3b\",\"digest\":\"abc123\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        return new OllamaPolicyAdvisor(mapper, "http://127.0.0.1:" + server.getAddress().getPort(), timeout, retries, 0.2, 300);
    }

    private static final AiPrompt PROMPT = new AiPrompt("inv-1", "cyc-1", "SYSTEM TEXT", "USER TEXT", "{}", "[]");

    @Test void sendsExactPromptsAndCapturesRawResponse() throws Exception {
        String envelope = "{\"model\":\"qwen2.5:3b\",\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"{\\\"decision\\\":\\\"PROPOSE\\\",\\\"admissionPerSecond\\\":55,\\\"maxQueue\\\":600,\\\"rationale\\\":\\\"r\\\"}\"},"
                + "\"prompt_eval_count\":120,\"eval_count\":30}";
        var advisor = start(200, envelope, 0, Duration.ofSeconds(5), 0);
        advisor.status("qwen2.5:3b");
        var r = advisor.advise(PROMPT, "qwen2.5:3b");

        assertEquals(AiInvocation.CallStatus.SUCCESS, r.status());
        assertEquals(envelope, r.rawResponse(), "raw body must be stored unmodified");
        assertEquals(received.get(), r.requestPayload(), "stored payload must equal bytes sent");
        assertTrue(received.get().contains("SYSTEM TEXT") && received.get().contains("USER TEXT"));
        assertEquals(EvolutionDecisionType.PROPOSE, r.decision().type());
        assertEquals(55, r.decision().admissionPerSecond());
        assertEquals(120, r.promptTokens());
        assertEquals(30, r.completionTokens());
        assertEquals("abc123", r.modelVersion());
        assertTrue(r.parameters().contains("temperature"));
    }

    @Test void unparsableModelAnswerIsReportedNotThrown() throws Exception {
        var advisor = start(200, "{\"message\":{\"content\":\"hello\"}}", 0, Duration.ofSeconds(5), 0);
        var r = advisor.advise(PROMPT, "m");
        assertNull(r.decision());
        assertNotNull(r.parseError());
    }

    @Test void serverErrorIsRetriedOnceThenFails() throws Exception {
        var advisor = start(500, "{}", 0, Duration.ofSeconds(5), 1);
        var r = advisor.advise(PROMPT, "m");
        assertEquals(AiInvocation.CallStatus.FAILED, r.status());
        assertTrue(r.error().contains("500"));
        assertEquals(2, chatCalls.get());
    }

    @Test void timeoutIsClassifiedAndNotRetried() throws Exception {
        var advisor = start(200, "{}", 1500, Duration.ofMillis(300), 2);
        var r = advisor.advise(PROMPT, "m");
        assertEquals(AiInvocation.CallStatus.TIMEOUT, r.status());
        assertEquals(1, chatCalls.get());
    }

    @Test void unreachableOllamaYieldsFailedResultAndDisconnectedStatus() {
        var advisor = new OllamaPolicyAdvisor(mapper, "http://127.0.0.1:1", Duration.ofSeconds(1), 0, 0.2, 300);
        assertEquals(AiInvocation.CallStatus.FAILED, advisor.advise(PROMPT, "m").status());
        assertFalse(advisor.status("m").reachable());
    }

    @Test void statusReportsModelAvailability() throws Exception {
        var advisor = start(200, "{}", 0, Duration.ofSeconds(1), 0);
        var s = advisor.status("qwen2.5:3b");
        assertTrue(s.reachable());
        assertTrue(s.selectedModelInstalled());
        assertFalse(advisor.status("other").selectedModelInstalled());
    }
}
