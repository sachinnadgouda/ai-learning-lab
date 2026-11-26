package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.time.Clock;
import java.util.Map;

/** Small helper shared by use cases for publishing consistent events. */
class EventSupport {
    private final EvolutionEventPublisher publisher;
    private final Clock clock;

    EventSupport(EvolutionEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    void emit(String level, String component, EvolutionStage stage, String cycleId, String invocationId,
              Integer policyVersion, String message, Map<String, Object> data) {
        publisher.publish(new EvolutionEvent(clock.instant(), level, component, stage, cycleId, invocationId,
                policyVersion, message, data));
    }

    void info(String component, EvolutionStage stage, String cycleId, String invocationId, Integer version,
              String message, Map<String, Object> data) {
        emit("INFO", component, stage, cycleId, invocationId, version, message, data);
    }
}
