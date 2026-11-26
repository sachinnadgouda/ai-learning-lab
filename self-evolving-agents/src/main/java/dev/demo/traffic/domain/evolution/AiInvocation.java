package dev.demo.traffic.domain.evolution;

import java.time.Instant;
import java.util.List;

/**
 * Exact record of one model call. {@code requestPayload} is the byte-for-byte JSON sent to the model (after
 * redaction) and {@code rawResponse} is the model's unmodified response body. Nothing is reconstructed.
 */
public record AiInvocation(String id, String cycleId, Instant createdAt, String model, String modelVersion,
                           String systemPrompt, String userPrompt, String requestPayload, String parameters,
                           String metricsContext, String historyContext, String rawResponse,
                           EvolutionDecisionType decision, Integer proposedAdmission, Integer proposedMaxQueue,
                           String rationale, ValidationStatus validationStatus, List<String> validationErrors,
                           String evaluation, String outcome, Integer policyVersionBefore, Integer candidateVersion,
                           long durationMs, Integer promptTokens, Integer completionTokens, CallStatus status,
                           String error, boolean redacted) {
    public enum ValidationStatus { VALID, INVALID, NOT_APPLICABLE }

    public enum CallStatus { SUCCESS, FAILED, TIMEOUT }

    public AiInvocation {
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    public AiInvocation withValidation(EvolutionDecision parsed, ValidationStatus vs, List<String> errors) {
        return new AiInvocation(id, cycleId, createdAt, model, modelVersion, systemPrompt, userPrompt, requestPayload,
                parameters, metricsContext, historyContext, rawResponse,
                parsed == null ? null : parsed.type(), parsed == null ? null : parsed.admissionPerSecond(),
                parsed == null ? null : parsed.maxQueue(), parsed == null ? null : parsed.rationale(), vs, errors,
                evaluation, outcome, policyVersionBefore, candidateVersion, durationMs, promptTokens, completionTokens,
                status, error, redacted);
    }

    public AiInvocation withOutcome(String newOutcome, Integer newCandidateVersion, String newEvaluationJson) {
        return new AiInvocation(id, cycleId, createdAt, model, modelVersion, systemPrompt, userPrompt, requestPayload,
                parameters, metricsContext, historyContext, rawResponse, decision, proposedAdmission, proposedMaxQueue,
                rationale, validationStatus, validationErrors, newEvaluationJson == null ? evaluation : newEvaluationJson,
                newOutcome, policyVersionBefore, newCandidateVersion, durationMs, promptTokens, completionTokens, status,
                error, redacted);
    }
}
