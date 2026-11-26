package dev.demo.traffic.domain.policy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A proposed policy derived from a specific baseline. It never becomes active without evaluation and approval. */
public record PolicyCandidate(TrafficPolicy policy, int baselineVersion, String source, String rationale,
                              String cycleId, Instant createdAt) {
    public PolicyCandidate {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        if (policy.version() != baselineVersion + 1) throw new IllegalArgumentException("Stale candidate version");
        if (policy.mode() != PolicyMode.EVOLVED) throw new IllegalArgumentException("Candidate mode must be EVOLVED");
    }

    public String fingerprint() {
        return policy.fingerprint();
    }

    /** Validates a candidate against the active policy. Returns human-readable violations; empty when acceptable. */
    public static List<String> violations(TrafficPolicy active, int admission, int maxQueue, boolean aiBounds) {
        var out = new java.util.ArrayList<String>();
        int minA = aiBounds ? PolicyBounds.AI_MIN_ADMISSION : 1;
        int maxA = aiBounds ? PolicyBounds.AI_MAX_ADMISSION : PolicyBounds.MAX_ADMISSION;
        int minQ = aiBounds ? PolicyBounds.AI_MIN_QUEUE : 0;
        int maxQ = aiBounds ? PolicyBounds.AI_MAX_QUEUE : PolicyBounds.MAX_QUEUE;
        if (admission < minA || admission > maxA) out.add("admissionPerSecond " + admission + " outside " + minA + ".." + maxA);
        if (maxQueue < minQ || maxQueue > maxQ) out.add("maxQueue " + maxQueue + " outside " + minQ + ".." + maxQ);
        if (out.isEmpty() && active.admissionPerSecond() == admission && active.maxQueue() == maxQueue
                && active.mode() == PolicyMode.EVOLVED) {
            out.add("No operational policy change");
        }
        return out;
    }

    public static PolicyCandidate of(TrafficPolicy active, int admission, int maxQueue, String source,
                                     String rationale, String cycleId, Instant now) {
        var next = new TrafficPolicy(active.version() + 1, PolicyMode.EVOLVED, admission, maxQueue, active.simulatedWorkMs());
        if (active.sameBehavior(next)) throw new IllegalArgumentException("No operational policy change");
        return new PolicyCandidate(next, active.version(), source, rationale, cycleId, now);
    }
}
