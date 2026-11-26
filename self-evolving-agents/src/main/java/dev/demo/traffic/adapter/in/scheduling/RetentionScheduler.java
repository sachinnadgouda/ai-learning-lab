package dev.demo.traffic.adapter.in.scheduling;

import dev.demo.traffic.adapter.out.jdbc.JdbcEvolutionEventRepository;
import dev.demo.traffic.application.usecase.AiInvocationQueries;
import dev.demo.traffic.config.DemoProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionScheduler.class);

    private final AiInvocationQueries invocations;
    private final JdbcEvolutionEventRepository events;
    private final DemoProperties props;

    public RetentionScheduler(AiInvocationQueries invocations, JdbcEvolutionEventRepository events, DemoProperties props) {
        this.invocations = invocations;
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${demo.retention.purge-interval:1h}", initialDelayString = "PT1M")
    public void purge() {
        try {
            int a = invocations.purge(props.retention().aiInvocations());
            int e = events.deleteOlderThan(Instant.now().minus(props.retention().events()));
            if (a + e > 0) LOG.info("Retention purge removed {} AI invocations and {} events", a, e);
        } catch (RuntimeException ex) {
            LOG.warn("Retention purge failed: {}", ex.getClass().getSimpleName());
        }
    }
}
