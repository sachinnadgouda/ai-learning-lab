package dev.demo.traffic.application.port.out;

import dev.demo.traffic.domain.evolution.EvolutionEvent;

/** Publishes lifecycle/diagnostic events (persisted, logged and metered by the adapter). */
public interface EvolutionEventPublisher {
    void publish(EvolutionEvent event);
}
