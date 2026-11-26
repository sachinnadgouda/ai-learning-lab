package dev.demo.traffic.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.domain.evolution.EvolutionDecision;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.evolution.GovernanceDecision;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import dev.demo.traffic.domain.traffic.AdmissionState;
import dev.demo.traffic.domain.traffic.QueueTicket;
import dev.demo.traffic.domain.traffic.TrafficObservation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmissionAndGovernanceTest {
    @Test void queuedDecisionNeedsATicket() {
        assertThrows(IllegalArgumentException.class, () -> new AdmissionDecision(AdmissionState.QUEUE, null, 1, 0, 0));
        var d = new AdmissionDecision(AdmissionState.QUEUE, new QueueTicket("t-1"), 3, 0, 0);
        assertEquals("t-1", d.ticketId());
        assertFalse(d.admitted());
    }

    @Test void blankTicketIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new QueueTicket(" "));
    }

    @Test void proposeRequiresBothParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvolutionDecision(EvolutionDecisionType.PROPOSE, 10, null, "x"));
        assertDoesNotThrow(() -> EvolutionDecision.noChange("fine"));
    }

    @Test void governanceNeedsAnActorAndFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new GovernanceDecision(GovernanceDecision.Outcome.APPROVED, "fp", " ", "", Instant.now()));
        assertThrows(NullPointerException.class, () -> new GovernanceDecision(GovernanceDecision.Outcome.APPROVED, null, "a", "", Instant.now()));
        assertEquals("", new GovernanceDecision(GovernanceDecision.Outcome.REJECTED, "fp", "a", null, Instant.now()).reason());
    }

    @Test void observationStatistics() {
        var o = new TrafficObservation(List.of(1, 5, 0, 4));
        assertEquals(10, o.total());
        assertEquals(5, o.peak());
        assertEquals(2.5, o.average());
        assertThrows(IllegalArgumentException.class, () -> new TrafficObservation(List.of(-1)));
    }

    @Test void terminalStages() {
        assertTrue(EvolutionStage.ROLLED_BACK.terminal());
        assertTrue(EvolutionStage.ACTIVATED.terminal());
        assertFalse(EvolutionStage.EVALUATED.terminal());
    }
}
