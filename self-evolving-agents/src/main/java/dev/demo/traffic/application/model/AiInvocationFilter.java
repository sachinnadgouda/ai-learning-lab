package dev.demo.traffic.application.model;

import java.time.Instant;

public record AiInvocationFilter(Instant from, Instant to, String decision, String status, String validation,
                                 String cycleId, String model, String text) {
    public static AiInvocationFilter none() {
        return new AiInvocationFilter(null, null, null, null, null, null, null, null);
    }
}
