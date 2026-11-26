package dev.demo.traffic.adapter.in.web.dto;

import dev.demo.traffic.domain.evolution.AiInvocation;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prompt/response fields are the exact recorded payloads. {@code aiRecommendation} is what the model said;
 * {@code independentValidation} is what the application verified afterwards.
 */
public record AiInvocationDetail(String id, String cycleId, Instant createdAt, Model model, Prompt prompt,
                                 String rawResponse, Recommendation aiRecommendation, Validation independentValidation,
                                 Timing timing, String callStatus, String error, boolean redacted,
                                 Integer policyVersionBefore) {
    public record Model(String name, String version) {}

    public record Prompt(String system, String user, String requestPayload, String parameters, String metricsContext,
                         String historyContext) {}

    public record Recommendation(String decision, Integer admissionPerSecond, Integer maxQueue, String rationale) {}

    public record Validation(String status, List<String> errors, JsonNode evaluation, String outcome, Integer candidateVersion) {}

    public record Timing(long durationMs, Integer promptTokens, Integer completionTokens) {}

    public static AiInvocationDetail of(AiInvocation i, JsonMapper mapper) {
        JsonNode evaluation = null;
        if (i.evaluation() != null) {
            try { evaluation = mapper.readTree(i.evaluation()); } catch (RuntimeException ignored) { /* kept as null */ }
        }
        return new AiInvocationDetail(i.id(), i.cycleId(), i.createdAt(), new Model(i.model(), i.modelVersion()),
                new Prompt(i.systemPrompt(), i.userPrompt(), i.requestPayload(), i.parameters(), i.metricsContext(),
                        i.historyContext()),
                i.rawResponse(),
                new Recommendation(i.decision() == null ? null : i.decision().name(), i.proposedAdmission(),
                        i.proposedMaxQueue(), i.rationale()),
                new Validation(i.validationStatus().name(), i.validationErrors(), evaluation, i.outcome(), i.candidateVersion()),
                new Timing(i.durationMs(), i.promptTokens(), i.completionTokens()), i.status().name(), i.error(),
                i.redacted(), i.policyVersionBefore());
    }
}
