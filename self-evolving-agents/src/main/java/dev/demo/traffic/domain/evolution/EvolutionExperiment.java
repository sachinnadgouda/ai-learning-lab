package dev.demo.traffic.domain.evolution;

import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Instant;

/** Durable record of one evolution cycle; recent ones are fed back to the AI as untrusted lessons. */
public record EvolutionExperiment(String cycleId, Instant startedAt, String trigger, EvolutionStage status,
                                  TrafficPolicy baseline, TrafficPolicy candidate, EvolutionDecisionType decision,
                                  String rationale, EvaluationResult evaluation, String aiInvocationId,
                                  long observedArrivals, String outcomeNote) {
    public EvolutionExperiment withStatus(EvolutionStage newStatus, String note) {
        return new EvolutionExperiment(cycleId, startedAt, trigger, newStatus, baseline, candidate, decision, rationale,
                evaluation, aiInvocationId, observedArrivals, note);
    }
}
