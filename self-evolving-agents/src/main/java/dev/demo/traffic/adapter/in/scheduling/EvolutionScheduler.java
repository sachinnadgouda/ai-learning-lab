package dev.demo.traffic.adapter.in.scheduling;

import dev.demo.traffic.application.usecase.ObserveTrafficUseCase;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Time-based triggers. All decisions (flags, thresholds) live in the use cases. */
@Component
public class EvolutionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(EvolutionScheduler.class);

    private final ProposePolicyUseCase propose;
    private final ObserveTrafficUseCase traffic;

    public EvolutionScheduler(ProposePolicyUseCase propose, ObserveTrafficUseCase traffic) {
        this.propose = propose;
        this.traffic = traffic;
    }

    @Scheduled(fixedDelayString = "${demo.evolution.interval-ms:60000}", initialDelayString = "${demo.evolution.interval-ms:60000}")
    public void evolve() {
        propose.runScheduled();
    }

    @Scheduled(fixedRate = 1000)
    public void sample() {
        try {
            traffic.sample();
        } catch (RuntimeException e) {
            LOG.debug("Traffic sampling skipped: {}", e.getClass().getSimpleName());
        }
    }

    /** Periodic summary instead of per-request INFO logs. Silent when idle. */
    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void logSummary() {
        try {
            var m = traffic.snapshot(30);
            if (m.incomingRps() > 0 || m.queueDepth() > 0) {
                LOG.info("Traffic summary window={}s incoming={}/s admitted={}/s rejected={}/s backendP95={}ms queueDepth={}",
                        m.windowSeconds(), fmt(m.incomingRps()), fmt(m.admittedRps()), fmt(m.rejectedRps()),
                        Math.round(m.p95LatencyMs()), m.queueDepth());
            }
        } catch (RuntimeException e) {
            LOG.warn("Traffic summary unavailable: {}", e.getClass().getSimpleName());
        }
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
