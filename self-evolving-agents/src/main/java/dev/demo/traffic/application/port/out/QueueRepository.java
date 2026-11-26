package dev.demo.traffic.application.port.out;

import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.AdmissionDecision;

public interface QueueRepository {
    /** Atomically decides admission under {@code policy}; {@code ticketId} is the caller's existing ticket or null. */
    AdmissionDecision admit(TrafficPolicy policy, String ticketId, String newTicketId, long nowMillis);

    long depth();
}
