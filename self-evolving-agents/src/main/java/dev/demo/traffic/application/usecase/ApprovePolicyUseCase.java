package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.application.port.out.OperationalMetricsPort;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.evolution.GovernanceDecision;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Clock;
import java.util.Map;

public class ApprovePolicyUseCase {
    private final PolicyRepository policies;
    private final ExperimentRepository experiments;
    private final OperationalMetricsPort metrics;
    private final EventSupport events;
    private final Clock clock;

    public ApprovePolicyUseCase(PolicyRepository policies, ExperimentRepository experiments, OperationalMetricsPort metrics,
                                EvolutionEventPublisher publisher, Clock clock) {
        this.policies = policies;
        this.experiments = experiments;
        this.metrics = metrics;
        this.clock = clock;
        this.events = new EventSupport(publisher, clock);
    }

    /**
     * Activates the pending candidate. Governance gates: the candidate must have been evaluated, the evaluation must be
     * valid and bound to the same fingerprint, the caller must name that fingerprint, and the baseline must still be active.
     */
    public synchronized TrafficPolicy approve(String actor, String expectedFingerprint, String reason) {
        PolicyCandidate candidate = policies.candidate().orElseThrow(() -> new NotFoundException("No candidate"));
        TrafficPolicy active = policies.active();
        if (expectedFingerprint == null || !candidate.fingerprint().equals(expectedFingerprint))
            throw new ConflictException("Approval does not match the pending candidate");
        EvaluationResult evaluation = policies.evaluation()
                .orElseThrow(() -> new ConflictException("Candidate has not been evaluated"));
        if (!evaluation.candidateFingerprint().equals(candidate.fingerprint()))
            throw new ConflictException("Evaluation belongs to a different candidate; re-evaluate");
        if (!evaluation.valid()) throw new ConflictException("Candidate failed evaluation");
        if (candidate.baselineVersion() != active.version() || evaluation.baselineVersion() != active.version())
            throw new ConflictException("Candidate is stale: the active policy changed");
        if (active.sameBehavior(candidate.policy())) throw new ConflictException("No operational change");

        var decision = new GovernanceDecision(GovernanceDecision.Outcome.APPROVED, candidate.fingerprint(), actor,
                reason, clock.instant());
        events.info("governance", EvolutionStage.APPROVED, candidate.cycleId(), null, candidate.policy().version(),
                "Candidate approved by " + decision.actor(), Map.of("actor", actor, "reason", decision.reason(),
                        "candidateFingerprint", candidate.fingerprint()));
        policies.activate(candidate.policy());
        metrics.policyApproved();
        metrics.activePolicyChanged(candidate.policy());
        events.info("governance", EvolutionStage.ACTIVATED, candidate.cycleId(), null, candidate.policy().version(),
                "Policy v" + candidate.policy().version() + " activated", Map.of("previousVersion", active.version()));
        if (candidate.cycleId() != null) {
            experiments.find(candidate.cycleId()).ifPresent(e -> experiments.save(
                    e.withStatus(EvolutionStage.ACTIVATED, "Approved by " + actor)));
        }
        return candidate.policy();
    }
}
