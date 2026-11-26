package dev.demo.traffic.domain.evolution;

import java.time.Instant;
import java.util.Objects;

/** Explicit human decision, bound to the exact candidate fingerprint that was evaluated. */
public record GovernanceDecision(Outcome outcome, String candidateFingerprint, String actor, String reason, Instant decidedAt) {
    public enum Outcome { APPROVED, REJECTED }

    public GovernanceDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(candidateFingerprint, "candidateFingerprint");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Actor required");
        reason = reason == null ? "" : reason;
    }
}
