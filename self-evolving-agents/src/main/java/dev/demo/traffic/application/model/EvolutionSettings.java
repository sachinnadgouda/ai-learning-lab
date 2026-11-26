package dev.demo.traffic.application.model;

import java.util.List;

/** Static (configuration) settings of the evolution engine. Runtime overrides live in the feature-flag port. */
public record EvolutionSettings(int backendCapacity, int minArrivalsForCycle, int defaultTriggerArrivals,
                                boolean aiDefaultEnabled, String defaultModel, List<String> allowedModels,
                                int lessonsLimit, int windowSeconds) {
    public static final int MIN_TRIGGER = 20;
    public static final int MAX_TRIGGER = 100_000;

    public EvolutionSettings {
        allowedModels = List.copyOf(allowedModels);
    }
}
