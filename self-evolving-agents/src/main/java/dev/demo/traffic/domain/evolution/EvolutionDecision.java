package dev.demo.traffic.domain.evolution;

import java.util.Objects;

/** A recommendation (AI or deterministic). PROPOSE carries both numeric parameters; others carry none. */
public record EvolutionDecision(EvolutionDecisionType type, Integer admissionPerSecond, Integer maxQueue, String rationale) {
    public EvolutionDecision {
        Objects.requireNonNull(type, "type");
        if (type == EvolutionDecisionType.PROPOSE && (admissionPerSecond == null || maxQueue == null))
            throw new IllegalArgumentException("PROPOSE requires admissionPerSecond and maxQueue");
        rationale = rationale == null ? "" : rationale;
    }

    public static EvolutionDecision noChange(String rationale) {
        return new EvolutionDecision(EvolutionDecisionType.NO_CHANGE, null, null, rationale);
    }

    public static EvolutionDecision insufficientData(String rationale) {
        return new EvolutionDecision(EvolutionDecisionType.INSUFFICIENT_DATA, null, null, rationale);
    }
}
