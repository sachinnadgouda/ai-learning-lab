package dev.demo.traffic.domain.traffic;

import java.util.Objects;

public record QueueTicket(String id) {
    public QueueTicket {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Ticket id must not be blank");
    }
}
