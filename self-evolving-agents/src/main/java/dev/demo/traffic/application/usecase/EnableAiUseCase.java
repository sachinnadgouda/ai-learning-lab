package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.model.FeatureFlags;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.FeatureFlagRepository;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.time.Clock;
import java.util.Map;

/** Disabling AI only stops new model calls; an already approved policy stays active. */
public class EnableAiUseCase {
    private final FeatureFlagRepository repository;
    private final EffectiveFlags flags;
    private final EventSupport events;

    public EnableAiUseCase(FeatureFlagRepository repository, EffectiveFlags flags, EvolutionEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.flags = flags;
        this.events = new EventSupport(publisher, clock);
    }

    public FeatureFlags setEnabled(boolean enabled, String actor) {
        repository.setAiEnabled(enabled);
        events.info("flags", EvolutionStage.DIAGNOSTIC, null, null, null, "AI feature flag set to " + enabled,
                Map.of("flag", "ai", "enabled", enabled, "actor", actor));
        return flags.snapshot();
    }
}
