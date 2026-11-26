package dev.demo.traffic.application.support;

import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.usecase.*;
import dev.demo.traffic.domain.evolution.ReplayEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/** Wires all use cases to in-memory fakes. */
public class Harness {
    public final Fakes.Policies policies = new Fakes.Policies();
    public final Fakes.Experiments experiments = new Fakes.Experiments();
    public final Fakes.Traffic traffic = new Fakes.Traffic();
    public final Fakes.Queue queue = new Fakes.Queue();
    public final Fakes.Flags flagStore = new Fakes.Flags();
    public final Fakes.Events events = new Fakes.Events();
    public final Fakes.Invocations invocations = new Fakes.Invocations();
    public final Fakes.Metrics metrics = new Fakes.Metrics();
    public final Fakes.Advisor advisor = new Fakes.Advisor();
    public final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    public final EvolutionSettings settings = new EvolutionSettings(50, 20, 120, false, "test-model",
            List.of("test-model", "other-model"), 5, 60);
    public final EffectiveFlags flags = new EffectiveFlags(flagStore, settings);
    public final EvaluatePolicyUseCase evaluate = new EvaluatePolicyUseCase(policies, traffic, metrics,
            new ReplayEvaluator(), settings, events, clock);
    public final ProposePolicyUseCase propose = new ProposePolicyUseCase(policies, experiments, traffic, queue, advisor,
            invocations, metrics, flags, evaluate, new PromptBuilder(new Fakes.Json()), new Redactor(), new Fakes.Json(),
            settings, events, clock);
    public final ApprovePolicyUseCase approve = new ApprovePolicyUseCase(policies, experiments, metrics, events, clock);
    public final RejectPolicyUseCase reject = new RejectPolicyUseCase(policies, experiments, events, clock);
    public final RollbackPolicyUseCase rollback = new RollbackPolicyUseCase(policies, experiments, metrics, events, clock);
    public final EnableAiUseCase enableAi = new EnableAiUseCase(flagStore, flags, events, clock);
    public final EnableAutomaticEvolutionUseCase enableAuto = new EnableAutomaticEvolutionUseCase(flagStore, flags, events, clock);
    public final ConfigureEvolutionUseCase configure = new ConfigureEvolutionUseCase(flagStore, flags, settings, events, clock);

    public Harness steadyTraffic(int perSecond) {
        traffic.arrivals = Collections.nCopies(60, perSecond);
        return this;
    }
}
