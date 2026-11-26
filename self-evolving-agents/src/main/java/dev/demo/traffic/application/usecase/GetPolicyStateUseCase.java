package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.model.PolicyState;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import java.util.Optional;

public class GetPolicyStateUseCase {
    private final PolicyRepository policies;

    public GetPolicyStateUseCase(PolicyRepository policies) {
        this.policies = policies;
    }

    public PolicyState state() {
        Optional<PolicyCandidate> candidate = policies.candidate();
        Optional<EvaluationResult> evaluation = policies.evaluation();
        boolean approvable = candidate.isPresent() && evaluation.isPresent()
                && evaluation.get().valid()
                && evaluation.get().candidateFingerprint().equals(candidate.get().fingerprint())
                && candidate.get().baselineVersion() == policies.active().version();
        return new PolicyState(policies.active(), candidate.orElse(null), evaluation.orElse(null), approvable,
                policies.history());
    }
}
