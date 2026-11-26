package dev.demo.traffic.adapter.out.ollama;

import dev.demo.traffic.application.model.AiCallResult;
import dev.demo.traffic.application.model.AiModelStatus;
import dev.demo.traffic.application.model.AiPrompt;
import dev.demo.traffic.application.port.out.AiPolicyAdvisorPort;
import dev.demo.traffic.domain.evolution.AiInvocation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ollama /api/chat adapter with a bounded timeout and a single controlled retry on transport errors. */
public class OllamaPolicyAdvisor implements AiPolicyAdvisorPort {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaPolicyAdvisor.class);
    private static final long STATUS_CACHE_MS = 10_000;

    private final JsonMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final double temperature;
    private final int numPredict;
    private volatile long statusAt;
    private volatile AiModelStatus cachedStatus;
    private volatile Map<String, String> digests = Map.of();

    public OllamaPolicyAdvisor(JsonMapper mapper, String baseUrl, Duration timeout, int maxRetries, double temperature,
                               int numPredict) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.maxRetries = Math.max(0, Math.min(maxRetries, 2));
        this.temperature = temperature;
        this.numPredict = numPredict;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    @Override
    public AiCallResult advise(AiPrompt prompt, String model) {
        MDC.put("aiInvocationId", prompt.invocationId());
        MDC.put("evolutionCycleId", prompt.cycleId());
        long started = System.nanoTime();
        var options = new LinkedHashMap<String, Object>();
        options.put("temperature", temperature);
        options.put("num_predict", numPredict);
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", prompt.systemPrompt()),
                Map.of("role", "user", "content", prompt.userPrompt())));
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", options);
        String requestJson = mapper.writeValueAsString(body);
        String parameters = mapper.writeValueAsString(Map.of("temperature", temperature, "num_predict", numPredict,
                "format", "json", "timeoutSeconds", timeout.toSeconds(), "maxRetries", maxRetries));
        String version = digests.get(model);
        try {
            LOG.info("Sending AI request to model {}", model);
            String responseBody = null;
            String failure = null;
            AiInvocation.CallStatus failureStatus = AiInvocation.CallStatus.FAILED;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat")).timeout(timeout)
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    responseBody = response.body();
                    if (response.statusCode() == 200) { failure = null; break; }
                    failure = "Ollama returned HTTP " + response.statusCode();
                    if (response.statusCode() < 500) break;
                } catch (HttpTimeoutException e) {
                    failure = "Ollama request timed out after " + timeout.toSeconds() + "s";
                    failureStatus = AiInvocation.CallStatus.TIMEOUT;
                    break;
                } catch (IOException e) {
                    failure = "Ollama unreachable: " + e.getClass().getSimpleName();
                    LOG.warn("Ollama attempt {} failed: {}", attempt + 1, failure);
                    if (attempt < maxRetries) sleep(500L * (attempt + 1));
                }
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (failure != null) {
                LOG.warn("AI request failed: {}", failure);
                return new AiCallResult(model, version, requestJson, parameters, responseBody, null, null, ms, null, null,
                        failureStatus, failure);
            }
            JsonNode reply;
            try {
                reply = mapper.readTree(responseBody);
            } catch (RuntimeException e) {
                return new AiCallResult(model, version, requestJson, parameters, responseBody, null,
                        "Ollama envelope is not valid JSON", ms, null, null, AiInvocation.CallStatus.SUCCESS, null);
            }
            var parsed = OllamaResponseParser.parse(mapper, reply.path("message").path("content").asString(""));
            Integer promptTokens = reply.has("prompt_eval_count") ? reply.path("prompt_eval_count").asInt() : null;
            Integer completionTokens = reply.has("eval_count") ? reply.path("eval_count").asInt() : null;
            LOG.info("AI response received in {} ms (parsed={})", ms, parsed.decision() != null);
            return new AiCallResult(reply.path("model").asString(model), version, requestJson, parameters, responseBody,
                    parsed.decision(), parsed.error(), ms, promptTokens, completionTokens,
                    AiInvocation.CallStatus.SUCCESS, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AiCallResult(model, version, requestJson, parameters, null, null, null,
                    (System.nanoTime() - started) / 1_000_000, null, null, AiInvocation.CallStatus.FAILED,
                    "Ollama request interrupted");
        } finally {
            MDC.remove("aiInvocationId");
            MDC.remove("evolutionCycleId");
        }
    }

    @Override
    public synchronized AiModelStatus status(String selectedModel) {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && now - statusAt < STATUS_CACHE_MS) {
            return withSelection(cachedStatus, selectedModel);
        }
        AiModelStatus fresh;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                fresh = new AiModelStatus(false, baseUrl, List.of(), false, "HTTP " + response.statusCode());
            } else {
                var names = new ArrayList<String>();
                var map = new LinkedHashMap<String, String>();
                for (JsonNode m : mapper.readTree(response.body()).path("models")) {
                    String name = m.path("name").asString("");
                    names.add(name);
                    map.put(name, m.path("digest").asString(""));
                }
                digests = Map.copyOf(map);
                fresh = new AiModelStatus(true, baseUrl, names, names.contains(selectedModel), "Connected");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fresh = new AiModelStatus(false, baseUrl, List.of(), false, "Interrupted");
        } catch (IOException | RuntimeException e) {
            fresh = new AiModelStatus(false, baseUrl, List.of(), false, "Unreachable: " + e.getClass().getSimpleName());
        }
        cachedStatus = fresh;
        statusAt = now;
        return fresh;
    }

    private static AiModelStatus withSelection(AiModelStatus s, String model) {
        return new AiModelStatus(s.reachable(), s.baseUrl(), s.installedModels(), s.installedModels().contains(model), s.detail());
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
