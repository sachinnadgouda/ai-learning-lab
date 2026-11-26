package dev.demo.traffic.application.port.out;

import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.policy.TrafficPolicy;

/** Business-level counters/gauges. Implementations must not use unbounded label values. */
public interface OperationalMetricsPort {
    void aiInvocation(AiInvocation invocation);

    void validationRejected();

    void cycleStarted();

    void cycleCompleted(String status);

    void evaluationOutcome(boolean valid);

    void policyApproved();

    void policyRolledBack();

    void activePolicyChanged(TrafficPolicy policy);
}
