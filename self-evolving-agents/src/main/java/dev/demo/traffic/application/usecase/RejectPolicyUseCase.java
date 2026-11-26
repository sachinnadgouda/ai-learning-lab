package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.evolution.GovernanceDecision;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import java.time.Clock;
import java.util.Map;

public class RejectPolicyUseCase {
    private final PolicyRepository policies;
    private final ExperimentRepository experiments;
    private final EventSupport events;
    private final Clock clock;

    public RejectPolicyUseCase(PolicyRepository policies, ExperimentRepository experiments,
                               EvolutionEventPublisher publisher, Clock clock) {
        this.policies = policies;
        this.experiments = experiments;
        this.clock = clock;
        this.events = new EventSupport(publisher, clock);
    }

    public synchronized GovernanceDecision reject(String actor, String reason) {
        PolicyCandidate candidate = policies.candidate().orElseThrow(() -> new NotFoundException("No candidate"));
        var decision = new GovernanceDecision(GovernanceDecision.Outcome.REJECTED, candidate.fingerprint(), actor, reason,
                clock.instant());
        policies.discardCandidate();
        events.info("governance", EvolutionStage.REJECTED, candidate.cycleId(), null, candidate.policy().version(),
                "Candidate rejected by " + actor, Map.of("actor", actor, "reason", decision.reason()));
        if (candidate.cycleId() != null) {
            experiments.find(candidate.cycleId()).ifPresent(e -> experiments.save(
                    e.withStatus(EvolutionStage.REJECTED, "Rejected by " + actor + ": " + decision.reason())));
        }
        return decision;
    }
}
