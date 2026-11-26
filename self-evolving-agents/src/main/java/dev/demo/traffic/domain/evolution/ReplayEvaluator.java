package dev.demo.traffic.domain.evolution;

import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic, isolated queue replay. Backend latency and errors are estimates, not HTTP measurements. */
public class ReplayEvaluator {
    public static final String METHODOLOGY = "Identical arrivals replayed with 1-second FIFO queue model and 30-second drain; "
            + "backend p95 and error rate are modeled estimates, NOT real measured HTTP metrics";

    private static double percentile(List<Integer> values, double percentile) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    public EvaluationResult.Outcome simulate(TrafficPolicy policy, List<Integer> trace, int backendCapacity) {
        if (backendCapacity < 1 || policy.admissionPerSecond() < 1 || policy.maxQueue() < 0)
            throw new IllegalArgumentException("Capacity and policy limits must be positive");
        var queue = new ArrayDeque<Integer>();
        var waits = new ArrayList<Integer>();
        var latencySamples = new ArrayList<Integer>();
        long arrivals = 0, admitted = 0, rejected = 0, wait = 0, maxDepth = 0, overflow = 0, overloaded = 0;
        for (int t = 0; t < trace.size() + 30; t++) {
            int arriving = t < trace.size() ? trace.get(t) : 0;
            if (arriving < 0) throw new IllegalArgumentException("Negative arrivals");
            arrivals += arriving;
            int budget = policy.admissionPerSecond(), processed = 0;
            while (budget > 0 && !queue.isEmpty()) {
                int waited = t - queue.removeFirst();
                waits.add(waited);
                wait += waited;
                admitted++;
                processed++;
                budget--;
            }
            int direct = Math.min(arriving, budget);
            admitted += direct;
            processed += direct;
            arriving -= direct;
            if (!policy.usesWaitingRoom()) rejected += arriving;
            else {
                int room = Math.max(0, policy.maxQueue() - queue.size());
                int added = Math.min(room, arriving);
                for (int k = 0; k < added; k++) queue.addLast(t);
                rejected += arriving - added;
            }
            int extra = Math.max(0, processed - backendCapacity);
            overflow += extra;
            overloaded += extra;
            latencySamples.add((int) Math.round(policy.simulatedWorkMs() * (1.0 + 3.0 * extra / backendCapacity)));
            maxDepth = Math.max(maxDepth, queue.size());
        }
        return new EvaluationResult.Outcome(arrivals, admitted, rejected, queue.size(), wait, maxDepth, overflow,
                percentile(latencySamples, 0.95), admitted == 0 ? 0 : (double) overloaded / admitted, percentile(waits, 0.95));
    }

    /** Compares candidate and baseline on identical arrivals. The result is bound to the candidate's fingerprint. */
    public EvaluationResult compare(TrafficPolicy baseline, TrafficPolicy candidate, List<Integer> trace,
                                    int backendCapacity, String traceSource, Instant now) {
        var a = simulate(baseline, trace, backendCapacity);
        var b = simulate(candidate, trace, backendCapacity);
        var reasons = new ArrayList<String>();
        long total = trace.stream().mapToLong(Integer::longValue).sum();
        if (total < 20) reasons.add("INCONCLUSIVE: fewer than 20 arrivals");
        if (b.estimatedP95BackendMs() > 150) reasons.add("Estimated backend p95 exceeds 150 ms SLO");
        if (b.estimatedErrorRate() > 0.01) reasons.add("Estimated overload error fraction exceeds 1%");
        if (b.estimatedP95WaitSeconds() > 30) reasons.add("Estimated p95 queue wait exceeds 30 seconds");
        if (b.queuedAtEnd() > 0) reasons.add("Queue not drained after 30 seconds");
        if (b.admitted() < a.admitted()) reasons.add("Fewer completed requests than baseline");
        if (b.rejected() > a.rejected()) reasons.add("More rejected requests than baseline");
        if (b.admitted() == a.admitted() && b.rejected() == a.rejected()
                && b.estimatedP95BackendMs() >= a.estimatedP95BackendMs()
                && b.estimatedP95WaitSeconds() >= a.estimatedP95WaitSeconds())
            reasons.add("INCONCLUSIVE: no measurable modeled benefit");
        return new EvaluationResult(baseline.version(), candidate.version(), candidate.fingerprint(), a, b,
                reasons.isEmpty(), List.copyOf(reasons), METHODOLOGY, traceSource, backendCapacity, now);
    }
}
