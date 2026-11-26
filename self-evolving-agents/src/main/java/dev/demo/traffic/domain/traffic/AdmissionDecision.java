package dev.demo.traffic.domain.traffic;

import java.util.Objects;

/** Result of one admission attempt. {@code waitedMs} is set when a queued ticket is admitted. */
public record AdmissionDecision(AdmissionState state, QueueTicket ticket, long queueDepth, long waitedMs, int expiredPurged) {
    public AdmissionDecision {
        Objects.requireNonNull(state, "state");
        if (state == AdmissionState.QUEUE && ticket == null) throw new IllegalArgumentException("QUEUE requires a ticket");
    }

    public boolean admitted() {
        return state == AdmissionState.ADMIT;
    }

    public String ticketId() {
        return ticket == null ? null : ticket.id();
    }
}
