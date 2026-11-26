package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.OperationalMetricsPort;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public class RollbackPolicyUseCase {
    private final PolicyRepository policies;
    private final ExperimentRepository experiments;
    private final OperationalMetricsPort metrics;
    private final EventSupport events;

    public RollbackPolicyUseCase(PolicyRepository policies, ExperimentRepository experiments, OperationalMetricsPort metrics,
                                 EvolutionEventPublisher publisher, Clock clock) {
        this.policies = policies;
        this.experiments = experiments;
        this.metrics = metrics;
        this.events = new EventSupport(publisher, clock);
    }

    public synchronized TrafficPolicy rollback(String actor, String reason) {
        TrafficPolicy before = policies.active();
        TrafficPolicy restored = policies.rollback().orElseThrow(() -> new ConflictException("No previous version"));
        metrics.policyRolledBack();
        metrics.activePolicyChanged(restored);
        // Attach the rollback to the cycle that activated the policy being undone, when it is known.
        var origin = experiments.recent(100).stream()
                .filter(e -> e.status() == EvolutionStage.ACTIVATED && e.candidate() != null
                        && e.candidate().version() == before.version())
                .findFirst();
        origin.ifPresent(e -> experiments.save(e.withStatus(EvolutionStage.ROLLED_BACK,
                "Rolled back by " + actor + (reason == null || reason.isBlank() ? "" : ": " + reason))));
        events.info("governance", EvolutionStage.ROLLED_BACK, origin.map(e -> e.cycleId()).orElseGet(() -> UUID.randomUUID().toString()), null, restored.version(),
                "Rolled back v" + before.version() + " to v" + restored.version() + " by " + actor,
                Map.of("actor", actor, "reason", reason == null ? "" : reason, "fromVersion", before.version()));
        return restored;
    }
}
