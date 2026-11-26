package dev.demo.traffic.domain.evolution;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** One timeline / diagnostic entry. {@code data} must only contain values safe to display (no secrets). */
public record EvolutionEvent(Instant time, String level, String component, EvolutionStage stage, String cycleId,
                             String aiInvocationId, Integer policyVersion, String message, Map<String, Object> data) {
    public EvolutionEvent {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(stage, "stage");
        level = level == null ? "INFO" : level;
        component = component == null ? "evolution" : component;
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
