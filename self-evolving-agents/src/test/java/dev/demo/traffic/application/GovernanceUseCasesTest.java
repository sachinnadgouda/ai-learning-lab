package dev.demo.traffic.application;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.exception.InvalidRequestException;
import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.support.Harness;
import dev.demo.traffic.application.usecase.GetPolicyStateUseCase;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import dev.demo.traffic.domain.evolution.EvolutionDecision;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernanceUseCasesTest {
    private Harness h;

    @BeforeEach void setUp() {
        h = new Harness().steadyTraffic(40);
        h.flagStore.ai = true;
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30);
    }

    private String candidateFingerprint() {
        h.advisor.willReturn(new EvolutionDecision(EvolutionDecisionType.PROPOSE, 40, 500, "more"));
        return h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL).candidate().fingerprint();
    }

    @Test void approvalActivatesExactlyTheEvaluatedCandidate() {
        String fp = candidateFingerprint();
        var active = h.approve.approve("admin", fp, "looks good");
        assertEquals(2, active.version());
        assertEquals(40, h.policies.active.admissionPerSecond());
        assertTrue(h.policies.candidate().isEmpty());
        assertEquals(1, h.policies.history.size());
        assertTrue(h.events.stages().containsAll(java.util.List.of(EvolutionStage.APPROVED, EvolutionStage.ACTIVATED)));
        assertEquals(EvolutionStage.ACTIVATED, h.experiments.items.get(0).status());
        assertTrue(h.metrics.calls.contains("approved"));
    }

    @Test void unevaluatedCandidateCanNeverBeApproved() {
        var cycle = h.propose.proposeDeterministic();
        var ex = assertThrows(ConflictException.class, () -> h.approve.approve("admin", cycle.candidate().fingerprint(), ""));
        assertTrue(ex.getMessage().contains("not been evaluated"));
        assertEquals(1, h.policies.active.version());
    }

    @Test void approvalIsBoundToTheNamedFingerprint() {
        candidateFingerprint();
        assertThrows(ConflictException.class, () -> h.approve.approve("admin", "2:EVOLVED:999:1:30", ""));
        assertThrows(ConflictException.class, () -> h.approve.approve("admin", null, ""));
    }

    @Test void evaluationOfADifferentCandidateIsNotAccepted() {
        String fp = candidateFingerprint();
        var stale = h.policies.evaluation;
        h.policies.evaluation = new dev.demo.traffic.domain.evolution.EvaluationResult(1, 2, "2:EVOLVED:1:1:30",
                stale.baseline(), stale.candidate(), true, java.util.List.of(), "m", "t", 50, h.clock.instant());
        assertThrows(ConflictException.class, () -> h.approve.approve("admin", fp, ""));
    }

    @Test void failedEvaluationBlocksApproval() {
        String fp = candidateFingerprint();
        var e = h.policies.evaluation;
        h.policies.evaluation = new dev.demo.traffic.domain.evolution.EvaluationResult(e.baselineVersion(), e.candidateVersion(),
                e.candidateFingerprint(), e.baseline(), e.candidate(), false, java.util.List.of("bad"), "m", "t", 50, h.clock.instant());
        assertThrows(ConflictException.class, () -> h.approve.approve("admin", fp, ""));
    }

    @Test void staleCandidateCannotBeApprovedAfterBaselineChanged() {
        String fp = candidateFingerprint();
        h.policies.active = new TrafficPolicy(5, PolicyMode.EVOLVED, 33, 100, 30);
        assertThrows(ConflictException.class, () -> h.approve.approve("admin", fp, ""));
    }

    @Test void approveWithoutCandidateIsNotFound() {
        assertThrows(NotFoundException.class, () -> h.approve.approve("admin", "x", ""));
    }

    @Test void deterministicCandidateCanBeApprovedAfterExplicitEvaluation() {
        h.steadyTraffic(80);
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 80, 500, 30);
        var cycle = h.propose.proposeDeterministic();
        var evaluation = h.evaluate.evaluatePending();
        if (evaluation.valid()) {
            assertEquals(cycle.candidate().version(), h.approve.approve("admin", cycle.candidate().fingerprint(), "").version());
        } else {
            assertThrows(ConflictException.class, () -> h.approve.approve("admin", cycle.candidate().fingerprint(), ""));
        }
    }

    @Test void evaluatingWithTooFewArrivalsUsesALabelledSyntheticScenario() {
        h.steadyTraffic(0);
        h.propose.proposeDeterministic();
        assertEquals("SYNTHETIC_SCENARIO", h.evaluate.evaluatePending().traceSource());
    }

    @Test void rejectionDiscardsTheCandidateAndKeepsTheActivePolicy() {
        candidateFingerprint();
        var decision = h.reject.reject("admin", "not now");
        assertEquals("not now", decision.reason());
        assertTrue(h.policies.candidate().isEmpty());
        assertEquals(1, h.policies.active.version());
        assertEquals(EvolutionStage.REJECTED, h.experiments.items.get(0).status());
    }

    @Test void rollbackRestoresPreviousPolicy() {
        h.approve.approve("admin", candidateFingerprint(), "");
        var restored = h.rollback.rollback("admin", "regression");
        assertEquals(1, restored.version());
        assertEquals(20, h.policies.active.admissionPerSecond());
        assertTrue(h.events.stages().contains(EvolutionStage.ROLLED_BACK));
        assertTrue(h.metrics.calls.contains("rolledBack"));
        assertThrows(ConflictException.class, () -> h.rollback.rollback("admin", ""));
    }

    @Test void disablingAiDoesNotRollBackAnApprovedPolicy() {
        h.approve.approve("admin", candidateFingerprint(), "");
        h.enableAi.setEnabled(false, "admin");
        assertEquals(2, h.policies.active.version());
        assertEquals(40, h.policies.active.admissionPerSecond());
        assertFalse(h.flags.aiEnabled());
    }

    @Test void policyStateReportsApprovability() {
        var state = new GetPolicyStateUseCase(h.policies);
        assertFalse(state.state().approvable());
        candidateFingerprint();
        assertTrue(state.state().approvable());
    }

    @Test void flagsCombineRuntimeOverridesAndDefaults() {
        assertEquals("environment", h.flags.snapshot().automaticSource());
        h.enableAuto.setEnabled(true, "admin");
        var snap = h.flags.snapshot();
        assertTrue(snap.automaticEvolutionEnabled());
        assertEquals("runtime", snap.automaticSource());
        assertTrue(h.events.events.stream().anyMatch(e -> e.component().equals("flags")));
    }

    @Test void modelAndThresholdAreValidated() {
        assertEquals("other-model", h.configure.selectModel("other-model", "admin").model());
        assertThrows(InvalidRequestException.class, () -> h.configure.selectModel("evil-model", "admin"));
        assertThrows(InvalidRequestException.class, () -> h.configure.setTriggerArrivals(5, "admin"));
        assertEquals(500, h.configure.setTriggerArrivals(500, "admin").triggerArrivals());
    }
}
