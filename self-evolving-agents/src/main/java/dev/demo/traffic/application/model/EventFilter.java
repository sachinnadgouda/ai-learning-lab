package dev.demo.traffic.application.model;

import java.time.Instant;

public record EventFilter(Instant from, Instant to, String level, String cycleId, String aiInvocationId,
                          Integer policyVersion, String component, String text) {
    public static EventFilter none() {
        return new EventFilter(null, null, null, null, null, null, null, null);
    }
}
