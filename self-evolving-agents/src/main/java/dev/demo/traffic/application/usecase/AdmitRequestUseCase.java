package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.application.port.out.QueueRepository;
import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import java.time.Clock;
import java.util.UUID;

public class AdmitRequestUseCase {
    public record Admission(AdmissionDecision decision, TrafficPolicy policy) {}

    private final PolicyRepository policies;
    private final QueueRepository queue;
    private final TrafficMetricsPort traffic;
    private final Clock clock;

    public AdmitRequestUseCase(PolicyRepository policies, QueueRepository queue, TrafficMetricsPort traffic, Clock clock) {
        this.policies = policies;
        this.queue = queue;
        this.traffic = traffic;
        this.clock = clock;
    }

    /** @param ticketId existing queue ticket or null/blank for a new arrival */
    public Admission admit(String ticketId) {
        String ticket = ticketId == null || ticketId.isBlank() ? null : ticketId;
        if (ticket == null) traffic.recordArrival();
        TrafficPolicy policy = policies.active();
        AdmissionDecision decision = queue.admit(policy, ticket, UUID.randomUUID().toString(), clock.millis());
        traffic.recordDecision(decision);
        return new Admission(decision, policy);
    }
}
