package dev.demo.traffic.domain.evolution;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of replaying a candidate against its baseline. All backend figures are SIMULATED.
 * {@code candidateFingerprint} binds the result to exactly one candidate; approval must match it.
 */
public record EvaluationResult(int baselineVersion, int candidateVersion, String candidateFingerprint,
                               Outcome baseline, Outcome candidate, boolean valid, List<String> reasons,
                               String methodology, String traceSource, int backendCapacity, Instant evaluatedAt) {
    public static final String TRACE_OBSERVED = "OBSERVED_ARRIVALS";
    public static final String TRACE_SYNTHETIC = "SYNTHETIC_SCENARIO";

    public record Outcome(long arrivals, long admitted, long rejected, long queuedAtEnd, long cumulativeWaitSeconds,
                          long maxQueueDepth, long capacityOverflow, double estimatedP95BackendMs,
                          double estimatedErrorRate, double estimatedP95WaitSeconds) {
        public double completionRatio() {
            return arrivals == 0 ? 1 : (double) admitted / arrivals;
        }

        public double averageWaitSeconds() {
            return admitted == 0 ? 0 : (double) cumulativeWaitSeconds / admitted;
        }
    }
}
