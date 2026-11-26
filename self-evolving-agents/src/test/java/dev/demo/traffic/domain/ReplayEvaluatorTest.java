package dev.demo.traffic.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.ReplayEvaluator;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayEvaluatorTest {
    private final ReplayEvaluator evaluator = new ReplayEvaluator();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private EvaluationResult compare(TrafficPolicy a, TrafficPolicy b, List<Integer> trace) {
        return evaluator.compare(a, b, trace, 50, EvaluationResult.TRACE_OBSERVED, NOW);
    }

    @Test void staticPolicyRejectsOverflowAndQueuePolicyDoesNot() {
        var trace = Collections.nCopies(60, 100);
        var staticOut = evaluator.simulate(new TrafficPolicy(1, PolicyMode.STATIC, 60, 500, 30), trace, 50);
        var queued = evaluator.simulate(new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 5000, 30), trace, 50);
        assertTrue(staticOut.rejected() > 0);
        assertTrue(queued.rejected() <= staticOut.rejected());
    }

    @Test void higherAdmissionCanImproveCompletionsWhenCapacityAllows() {
        var result = compare(new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30),
                new TrafficPolicy(2, PolicyMode.EVOLVED, 40, 500, 30), Collections.nCopies(60, 40));
        assertTrue(result.valid(), result.reasons().toString());
        assertTrue(result.candidate().admitted() > result.baseline().admitted());
    }

    @Test void lowerAdmissionCanProtectBackendWhenOverloaded() {
        var trace = Collections.nCopies(60, 80);
        var base = evaluator.simulate(new TrafficPolicy(1, PolicyMode.STATIC, 80, 500, 30), trace, 50);
        var cand = evaluator.simulate(new TrafficPolicy(2, PolicyMode.EVOLVED, 45, 500, 30), trace, 50);
        assertTrue(cand.capacityOverflow() < base.capacityOverflow());
    }

    @Test void noImprovementIsInconclusive() {
        var result = compare(new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30),
                new TrafficPolicy(2, PolicyMode.EVOLVED, 25, 500, 30), Collections.nCopies(60, 10));
        assertFalse(result.valid());
        assertTrue(result.reasons().stream().anyMatch(r -> r.startsWith("INCONCLUSIVE")));
    }

    @Test void tooFewArrivalsIsInconclusive() {
        var result = compare(TrafficPolicy.defaultPolicy(), new TrafficPolicy(2, PolicyMode.EVOLVED, 70, 500, 30),
                Collections.nCopies(60, 0));
        assertFalse(result.valid());
    }

    @Test void resultIsBoundToTheCandidateAndLabelledSimulated() {
        var cand = new TrafficPolicy(2, PolicyMode.EVOLVED, 40, 500, 30);
        var result = compare(new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30), cand, Collections.nCopies(60, 40));
        assertEquals(cand.fingerprint(), result.candidateFingerprint());
        assertTrue(result.methodology().contains("NOT real measured"));
        assertEquals(1, result.baselineVersion());
    }

    @Test void negativeArrivalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.simulate(TrafficPolicy.defaultPolicy(), List.of(-1), 50));
    }

    @Test void identicalPolicyReplayIsDeterministic() {
        var p = TrafficPolicy.defaultPolicy();
        assertEquals(evaluator.simulate(p, Collections.nCopies(60, 70), 50), evaluator.simulate(p, Collections.nCopies(60, 70), 50));
    }
}
