package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.model.FeatureFlags;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.FeatureFlagRepository;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.time.Clock;
import java.util.Map;

public class EnableAutomaticEvolutionUseCase {
    private final FeatureFlagRepository repository;
    private final EffectiveFlags flags;
    private final EventSupport events;

    public EnableAutomaticEvolutionUseCase(FeatureFlagRepository repository, EffectiveFlags flags,
                                           EvolutionEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.flags = flags;
        this.events = new EventSupport(publisher, clock);
    }

    public FeatureFlags setEnabled(boolean enabled, String actor) {
        repository.setAutomaticEvolution(enabled);
        events.info("flags", EvolutionStage.DIAGNOSTIC, null, null, null, "Automatic evolution set to " + enabled,
                Map.of("flag", "automatic", "enabled", enabled, "actor", actor));
        return flags.snapshot();
    }
}
