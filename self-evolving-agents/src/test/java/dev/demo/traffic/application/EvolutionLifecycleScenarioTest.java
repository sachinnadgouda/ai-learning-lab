package dev.demo.traffic.application;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.application.support.Harness;
import dev.demo.traffic.application.usecase.AdmitRequestUseCase;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import dev.demo.traffic.domain.evolution.EvolutionDecision;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import dev.demo.traffic.domain.traffic.AdmissionState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic end-to-end scenario with a scripted advisor (no LLM involved). */
class EvolutionLifecycleScenarioTest {
    @Test void fullLifecycleFromObservationToRollback() {
        var h = new Harness().steadyTraffic(40);
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30);
        h.enableAi.setEnabled(true, "admin");

        h.advisor.willReturn(new EvolutionDecision(EvolutionDecisionType.PROPOSE, 40, 800, "arrivals exceed the limit"));
        var cycle = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);

        assertEquals(EvolutionStage.EVALUATED, cycle.status());
        assertEquals(List.of(EvolutionStage.DIAGNOSTIC, EvolutionStage.OBSERVED, EvolutionStage.TRIGGERED, EvolutionStage.AI_CALLED,
                        EvolutionStage.AI_CALLED, EvolutionStage.PROPOSED, EvolutionStage.EVALUATED),
                h.events.stages());

        h.approve.approve("admin", cycle.candidate().fingerprint(), "ok");
        assertEquals(40, h.policies.active.admissionPerSecond());
        assertTrue(h.events.stages().containsAll(List.of(EvolutionStage.APPROVED, EvolutionStage.ACTIVATED)));

        h.rollback.rollback("admin", "undo");
        assertEquals(20, h.policies.active.admissionPerSecond());
        assertEquals(EvolutionStage.ROLLED_BACK, h.events.stages().get(h.events.stages().size() - 1));
        assertEquals(EvolutionStage.ROLLED_BACK, h.experiments.items.get(0).status());
        assertEquals(cycle.cycleId(), h.events.events.get(h.events.events.size() - 1).cycleId(), "rollback joins the originating cycle");
        assertEquals(1, h.invocations.items.size());
        assertEquals(List.of("cycleStarted", "evaluation:true", "ai:SUCCESS", "cycleCompleted:AWAITING_APPROVAL"),
                h.metrics.calls.subList(0, 4));
    }

    @Test void admissionUsesTheActivePolicyAndOnlyCountsNewArrivals() {
        var h = new Harness();
        var admit = new AdmitRequestUseCase(h.policies, h.queue, h.traffic, h.clock);
        h.queue.next = new AdmissionDecision(AdmissionState.REJECT, null, 0, 0, 0);
        var r = admit.admit(null);
        assertEquals(AdmissionState.REJECT, r.decision().state());
        assertEquals(1, h.traffic.arrivalsRecorded.get());
        admit.admit("ticket-1");
        admit.admit("  ");
        assertEquals(2, h.traffic.arrivalsRecorded.get(), "ticket polls are not arrivals; blank is a new arrival");
        assertNull(h.queue.lastTicket);
        assertEquals(3, h.traffic.decisions.size());
    }
}
