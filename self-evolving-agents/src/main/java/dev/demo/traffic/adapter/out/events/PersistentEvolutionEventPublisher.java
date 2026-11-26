package dev.demo.traffic.adapter.out.events;

import dev.demo.traffic.adapter.out.jdbc.JdbcEvolutionEventRepository;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Writes every event to the structured log (with MDC correlation fields) and to the event store. */
public class PersistentEvolutionEventPublisher implements EvolutionEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger("dev.demo.traffic.evolution");

    private final JdbcEvolutionEventRepository repository;

    public PersistentEvolutionEventPublisher(JdbcEvolutionEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void publish(EvolutionEvent e) {
        try {
            put("evolutionCycleId", e.cycleId());
            put("aiInvocationId", e.aiInvocationId());
            put("policyVersion", e.policyVersion() == null ? null : e.policyVersion().toString());
            MDC.put("component", e.component());
            String line = "[{}] {}";
            switch (e.level()) {
                case "ERROR" -> LOG.error(line, e.stage(), e.message());
                case "WARN" -> LOG.warn(line, e.stage(), e.message());
                default -> LOG.info(line, e.stage(), e.message());
            }
        } finally {
            MDC.remove("evolutionCycleId");
            MDC.remove("aiInvocationId");
            MDC.remove("policyVersion");
            MDC.remove("component");
        }
        try {
            repository.save(e);
        } catch (RuntimeException ex) {
            LOG.error("Unable to persist evolution event {}: {}", e.stage(), ex.getClass().getSimpleName());
        }
    }

    private static void put(String key, String value) {
        if (value != null) MDC.put(key, value);
    }
}
