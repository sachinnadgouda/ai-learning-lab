package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.InvalidRequestException;
import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.model.FeatureFlags;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.FeatureFlagRepository;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.time.Clock;
import java.util.Map;

/** Model selection and the automatic-trigger threshold, both limited to safe values. */
public class ConfigureEvolutionUseCase {
    private final FeatureFlagRepository repository;
    private final EffectiveFlags flags;
    private final EvolutionSettings settings;
    private final EventSupport events;

    public ConfigureEvolutionUseCase(FeatureFlagRepository repository, EffectiveFlags flags, EvolutionSettings settings,
                                     EvolutionEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.flags = flags;
        this.settings = settings;
        this.events = new EventSupport(publisher, clock);
    }

    public FeatureFlags selectModel(String model, String actor) {
        if (!settings.allowedModels().contains(model))
            throw new InvalidRequestException("Model not in the configured allow-list: " + settings.allowedModels());
        repository.setModel(model);
        events.info("flags", EvolutionStage.DIAGNOSTIC, null, null, null, "AI model set to " + model,
                Map.of("flag", "model", "model", model, "actor", actor));
        return flags.snapshot();
    }

    public FeatureFlags setTriggerArrivals(int arrivals, String actor) {
        if (arrivals < EvolutionSettings.MIN_TRIGGER || arrivals > EvolutionSettings.MAX_TRIGGER)
            throw new InvalidRequestException("Trigger threshold must be " + EvolutionSettings.MIN_TRIGGER + ".."
                    + EvolutionSettings.MAX_TRIGGER);
        repository.setTriggerArrivals(arrivals);
        events.info("flags", EvolutionStage.DIAGNOSTIC, null, null, null, "Trigger threshold set to " + arrivals,
                Map.of("flag", "triggerArrivals", "value", arrivals, "actor", actor));
        return flags.snapshot();
    }
}
