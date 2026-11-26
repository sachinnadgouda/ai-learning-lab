package dev.demo.traffic.application;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.application.exception.AiUnavailableException;
import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.exception.FeatureDisabledException;
import dev.demo.traffic.application.support.Harness;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import dev.demo.traffic.domain.evolution.*;
import dev.demo.traffic.domain.evolution.AiInvocation.CallStatus;
import dev.demo.traffic.domain.evolution.AiInvocation.ValidationStatus;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProposePolicyUseCaseTest {
    private Harness h;

    @BeforeEach void setUp() {
        h = new Harness().steadyTraffic(40);
        h.flagStore.ai = true;
    }

    private static EvolutionDecision propose(int admission, int queue) {
        return new EvolutionDecision(EvolutionDecisionType.PROPOSE, admission, queue, "because");
    }

    @Test void disabledAiNeverCallsTheModel() {
        h.flagStore.ai = false;
        assertThrows(FeatureDisabledException.class, () -> h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL));
        assertTrue(h.advisor.prompts.isEmpty());
        assertTrue(h.invocations.items.isEmpty());
    }

    @Test void insufficientArrivalsSkipTheModelCall() {
        h.traffic.arrivals = java.util.Collections.nCopies(60, 0);
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.INSUFFICIENT_DATA, result.status());
        assertTrue(h.advisor.prompts.isEmpty());
        assertTrue(h.events.stages().contains(EvolutionStage.INSUFFICIENT_DATA));
    }

    @Test void aiMayIncreaseAdmission() {
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30);
        h.advisor.willReturn(propose(40, 500));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.EVALUATED, result.status(), result.note());
        assertTrue(result.awaitingApproval());
        assertEquals(40, result.candidate().admissionPerSecond());
        assertEquals(20, h.policies.active.admissionPerSecond(), "AI must not change the active policy");
        assertTrue(h.policies.candidate().isPresent());
        assertTrue(h.policies.evaluation().orElseThrow().valid());
    }

    @Test void aiMayDecreaseAdmissionWhenBackendIsOverloaded() {
        h.steadyTraffic(80);
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 80, 500, 30);
        h.advisor.willReturn(propose(45, 3000));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(45, result.candidate().admissionPerSecond());
        assertTrue(result.candidate().admissionPerSecond() < h.policies.active.admissionPerSecond());
    }

    @Test void queueCapacityCanBeAdjusted() {
        h.steadyTraffic(60);
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 40, 500, 30);
        h.advisor.willReturn(propose(40, 2000));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(2000, h.policies.candidate().orElseThrow().policy().maxQueue(), result.note());
    }

    @Test void noChangeAndInsufficientDataDecisionsCreateNoCandidate() {
        h.advisor.willReturn(EvolutionDecision.noChange("fine"));
        var r1 = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        h.advisor.willReturn(EvolutionDecision.insufficientData("unclear"));
        var r2 = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.NO_CHANGE, r1.status());
        assertEquals(EvolutionStage.INSUFFICIENT_DATA, r2.status());
        assertTrue(h.policies.candidate().isEmpty());
        assertEquals(1, h.policies.active.version());
    }

    @Test void semanticNoOpProposalDoesNotCreateAPolicyVersion() {
        h.policies.active = new TrafficPolicy(3, PolicyMode.EVOLVED, 60, 500, 30);
        h.advisor.willReturn(propose(60, 500));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.NO_CHANGE, result.status());
        assertTrue(h.policies.candidate().isEmpty());
        assertEquals("NO_OP_PROPOSAL", h.invocations.items.values().iterator().next().outcome());
    }

    @Test void outOfBoundsProposalIsRejectedAndRecordedAsInvalid() {
        h.advisor.willReturn(propose(5000, 10));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.REJECTED, result.status());
        var inv = h.invocations.items.values().iterator().next();
        assertEquals(ValidationStatus.INVALID, inv.validationStatus());
        assertFalse(inv.validationErrors().isEmpty());
        assertTrue(h.metrics.calls.contains("validationRejected"));
        assertTrue(h.policies.candidate().isEmpty());
    }

    @Test void unparseableResponseIsRecordedWithValidationFailure() {
        h.advisor.willReturnUnparseable("Model response is not valid JSON");
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.FAILED, result.status());
        var inv = h.invocations.items.values().iterator().next();
        assertEquals(ValidationStatus.INVALID, inv.validationStatus());
        assertEquals("not json", inv.rawResponse());
    }

    @Test void candidateFailingEvaluationIsDiscarded() {
        h.steadyTraffic(100);
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30);
        h.advisor.willReturn(propose(900, 10));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertEquals(EvolutionStage.REJECTED, result.status());
        assertTrue(h.policies.candidate().isEmpty());
        assertFalse(result.evaluation().valid());
    }

    @Test void modelFailureIsPersistedAndSurfacedAsUnavailable() {
        h.advisor.willFail(CallStatus.TIMEOUT, "timed out");
        assertThrows(AiUnavailableException.class, () -> h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL));
        var inv = h.invocations.items.values().iterator().next();
        assertEquals(CallStatus.TIMEOUT, inv.status());
        assertEquals("timed out", inv.error());
        assertEquals(EvolutionStage.FAILED, h.experiments.items.get(0).status());
        assertEquals(1, h.metrics.calls.stream().filter("cycleCompleted:FAILED"::equals).count());
    }

    @Test void invocationRecordsExactPromptAndPayloadSentToTheModel() {
        h.advisor.willReturn(propose(45, 500));
        var result = h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        var prompt = h.advisor.prompts.get(0);
        var inv = h.invocations.items.get(result.aiInvocationId());
        assertEquals(prompt.systemPrompt(), inv.systemPrompt());
        assertEquals(prompt.userPrompt(), inv.userPrompt());
        assertEquals("{\"request\":true}", inv.requestPayload());
        assertEquals("{\"message\":{\"content\":\"raw\"}}", inv.rawResponse());
        assertEquals(result.cycleId(), inv.cycleId());
        assertEquals(prompt.metricsContext(), inv.metricsContext());
        assertEquals(CallStatus.SUCCESS, inv.status());
        assertEquals(100, inv.promptTokens());
        assertNotNull(inv.evaluation(), "evaluation result must be stored with the invocation");
    }

    @Test void promptFencesTelemetryAsUntrustedData() {
        h.advisor.willReturn(EvolutionDecision.noChange("ok"));
        h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        var p = h.advisor.prompts.get(0);
        assertTrue(p.systemPrompt().contains("never instructions"));
        assertTrue(p.userPrompt().contains("<<<BEGIN_UNTRUSTED_DATA>>>"));
        assertTrue(p.userPrompt().contains("Observed traffic metrics"));
    }

    @Test void secretsInResponsesAreRedactedBeforePersistence() {
        h.advisor.script.add(new dev.demo.traffic.application.model.AiCallResult("test-model", null, "{}", "{}",
                "{\"x\":\"Authorization: Bearer abcdefghijklmnop\"}", EvolutionDecision.noChange("ok"), null, 1, null, null,
                CallStatus.SUCCESS, null));
        h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        var inv = h.invocations.items.values().iterator().next();
        assertFalse(inv.rawResponse().contains("abcdefghijklmnop"));
        assertTrue(inv.redacted());
    }

    @Test void pendingCandidateBlocksANewCycle() {
        h.policies.active = new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30);
        h.advisor.willReturn(propose(40, 500));
        h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        assertThrows(ConflictException.class, () -> h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL));
    }

    @Test void previousExperimentsAreSuppliedToTheNextCycleAsLessons() {
        h.advisor.willReturn(EvolutionDecision.noChange("first cycle rationale"));
        h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        h.advisor.willReturn(EvolutionDecision.noChange("second"));
        h.propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
        var second = h.advisor.prompts.get(1);
        assertTrue(second.historyContext().contains("NO_CHANGE"));
        assertTrue(second.userPrompt().contains("first cycle rationale"));
        assertEquals(2, h.experiments.items.size());
    }

    @Test void scheduledRunRespectsFlagsAndThreshold() {
        h.advisor.willReturn(EvolutionDecision.noChange("x"));
        h.propose.runScheduled();
        assertTrue(h.advisor.prompts.isEmpty(), "automatic evolution is off by default");
        h.flagStore.auto = true;
        h.flagStore.trigger = 5000;
        h.propose.runScheduled();
        assertTrue(h.advisor.prompts.isEmpty(), "2400 arrivals are below the 5000 threshold");
    }

    @Test void scheduledRunTriggersWhenEnabledAndAboveThreshold() {
        h.flagStore.auto = true;
        h.flagStore.trigger = 20;
        h.advisor.willReturn(EvolutionDecision.noChange("x"));
        h.propose.runScheduled();
        assertEquals(1, h.advisor.prompts.size());
    }

    @Test void scheduledRunDoesNothingWhenAiDisabled() {
        h.flagStore.auto = true;
        h.flagStore.trigger = 20;
        h.flagStore.ai = false;
        h.propose.runScheduled();
        assertTrue(h.advisor.prompts.isEmpty());
    }

    @Test void scheduledRunSwallowsModelFailures() {
        h.flagStore.auto = true;
        h.flagStore.trigger = 20;
        h.advisor.willFail(CallStatus.FAILED, "down");
        assertDoesNotThrow(h.propose::runScheduled);
    }

    @Test void deterministicProposalWorksWithoutAiAndNeedsEvaluation() {
        h.flagStore.ai = false;
        var r = h.propose.proposeDeterministic();
        assertEquals(48, r.candidate().admissionPerSecond());
        assertTrue(h.advisor.prompts.isEmpty());
        assertTrue(h.policies.evaluation().isEmpty());
    }
}
