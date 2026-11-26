package dev.demo.traffic.application.port.out;

import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {
    TrafficPolicy active();

    Optional<PolicyCandidate> candidate();

    void saveCandidate(PolicyCandidate candidate);

    /** Removes the candidate and its bound evaluation. */
    void discardCandidate();

    Optional<EvaluationResult> evaluation();

    void saveEvaluation(EvaluationResult evaluation);

    /** Pushes the current active policy onto the history and activates {@code next}; also clears the candidate. */
    void activate(TrafficPolicy next);

    /** Restores the previous policy from history, or empty when there is none. Also clears the candidate. */
    Optional<TrafficPolicy> rollback();

    /** Previous policies, most recent first. */
    List<TrafficPolicy> history();
}
