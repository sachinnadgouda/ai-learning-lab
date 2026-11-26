package dev.demo.traffic.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.domain.policy.PolicyBounds;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrafficPolicyTest {
    @Test void rejectsValuesOutsideSafetyBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficPolicy(0, PolicyMode.STATIC, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrafficPolicy(1, PolicyMode.STATIC, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrafficPolicy(1, PolicyMode.STATIC, PolicyBounds.MAX_ADMISSION + 1, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrafficPolicy(1, PolicyMode.STATIC, 10, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrafficPolicy(1, PolicyMode.STATIC, 10, 10, 1001));
        assertThrows(NullPointerException.class, () -> new TrafficPolicy(1, null, 10, 10, 10));
    }

    @Test void versionOnlyChangeIsNoOp() {
        assertTrue(new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 500, 30)
                .sameBehavior(new TrafficPolicy(3, PolicyMode.EVOLVED, 60, 500, 30)));
    }

    @Test void changingModeIsARealChange() {
        assertFalse(new TrafficPolicy(1, PolicyMode.STATIC, 60, 500, 30)
                .sameBehavior(new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 500, 30)));
    }

    @Test void fingerprintIdentifiesParametersAndVersion() {
        var a = new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 500, 30);
        assertEquals(a.fingerprint(), new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 500, 30).fingerprint());
        assertNotEquals(a.fingerprint(), new TrafficPolicy(2, PolicyMode.EVOLVED, 61, 500, 30).fingerprint());
    }

    @Test void candidateMustBeNextEvolvedVersion() {
        var active = TrafficPolicy.defaultPolicy();
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new PolicyCandidate(
                new TrafficPolicy(3, PolicyMode.EVOLVED, 10, 10, 30), 1, "t", "r", "c", now));
        assertThrows(IllegalArgumentException.class, () -> new PolicyCandidate(
                new TrafficPolicy(2, PolicyMode.STATIC, 10, 10, 30), 1, "t", "r", "c", now));
        assertEquals(2, PolicyCandidate.of(active, 100, 800, "t", "r", "c", now).policy().version());
    }

    @Test void identicalCandidateIsRejectedAsNoOp() {
        var active = new TrafficPolicy(2, PolicyMode.EVOLVED, 60, 500, 30);
        assertThrows(IllegalArgumentException.class, () -> PolicyCandidate.of(active, 60, 500, "t", "r", "c", Instant.now()));
    }

    @Test void aiBoundsAreStricterThanHardBounds() {
        var active = TrafficPolicy.defaultPolicy();
        assertTrue(PolicyCandidate.violations(active, 1500, 100, true).size() == 1);
        assertTrue(PolicyCandidate.violations(active, 1500, 100, false).isEmpty());
        assertEquals(1, PolicyCandidate.violations(active, 100, 0, true).size());
        assertTrue(PolicyCandidate.violations(active, 1000, 10000, true).isEmpty());
    }
}
