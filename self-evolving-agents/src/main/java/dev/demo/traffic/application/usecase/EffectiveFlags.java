package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.model.FeatureFlags;
import dev.demo.traffic.application.port.out.FeatureFlagRepository;

/** Resolves runtime overrides against configuration defaults. */
public class EffectiveFlags {
    private final FeatureFlagRepository repository;
    private final EvolutionSettings settings;

    public EffectiveFlags(FeatureFlagRepository repository, EvolutionSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    public boolean aiEnabled() {
        return repository.aiEnabled().orElse(settings.aiDefaultEnabled());
    }

    public boolean automaticEnabled() {
        return repository.automaticEvolution().orElse(false);
    }

    public String model() {
        return repository.model().filter(settings.allowedModels()::contains).orElse(settings.defaultModel());
    }

    public int triggerArrivals() {
        return repository.triggerArrivals().orElse(settings.defaultTriggerArrivals());
    }

    public FeatureFlags snapshot() {
        return new FeatureFlags(aiEnabled(), repository.aiEnabled().isPresent() ? "runtime" : "environment",
                automaticEnabled(), repository.automaticEvolution().isPresent() ? "runtime" : "environment",
                model(), repository.model().isPresent() ? "runtime" : "environment",
                triggerArrivals(), repository.triggerArrivals().isPresent() ? "runtime" : "environment");
    }
}
