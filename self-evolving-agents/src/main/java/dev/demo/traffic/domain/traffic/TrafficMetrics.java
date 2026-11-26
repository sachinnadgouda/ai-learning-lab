package dev.demo.traffic.domain.traffic;

import java.time.Instant;

/**
 * Metrics measured by this process over a window. Backend latency and errors are measured on the in-process
 * simulated backend, never modelled. Counters are process-local and reset on restart.
 */
public record TrafficMetrics(Instant observedAt, int windowSeconds, double incomingRps, double admittedRps,
                             double rejectedRps, double backendThroughputRps, double p50LatencyMs, double p95LatencyMs,
                             double p99LatencyMs, double backend5xxRate, int backendConcurrency, long queueDepth,
                             double queueP95WaitSeconds, String source) {
    public static final String OBSERVED = "OBSERVED";
}
