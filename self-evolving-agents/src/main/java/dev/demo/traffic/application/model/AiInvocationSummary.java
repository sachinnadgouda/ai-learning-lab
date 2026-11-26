package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.AiInvocation;
import java.time.Instant;

/** List-view projection; the exact prompt and response are only available from the detail view. */
public record AiInvocationSummary(String id, String cycleId, Instant createdAt, String model, String decision,
                                  Integer proposedAdmission, Integer proposedMaxQueue, String validationStatus,
                                  String status, String outcome, long durationMs, Integer totalTokens) {
    public static AiInvocationSummary of(AiInvocation i) {
        Integer tokens = i.promptTokens() == null && i.completionTokens() == null ? null
                : (i.promptTokens() == null ? 0 : i.promptTokens()) + (i.completionTokens() == null ? 0 : i.completionTokens());
        return new AiInvocationSummary(i.id(), i.cycleId(), i.createdAt(), i.model(),
                i.decision() == null ? null : i.decision().name(), i.proposedAdmission(), i.proposedMaxQueue(),
                i.validationStatus().name(), i.status().name(), i.outcome(), i.durationMs(), tokens);
    }
}
