package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.AiUnavailableException;
import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.exception.FeatureDisabledException;
import dev.demo.traffic.application.model.AiCallResult;
import dev.demo.traffic.application.model.AiPrompt;
import dev.demo.traffic.application.model.CycleResult;
import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.port.out.AiInvocationRepository;
import dev.demo.traffic.application.port.out.AiPolicyAdvisorPort;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.application.port.out.JsonCodec;
import dev.demo.traffic.application.port.out.OperationalMetricsPort;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.application.port.out.QueueRepository;
import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.EvolutionDecision;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionExperiment;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.TrafficMetrics;
import dev.demo.traffic.domain.traffic.TrafficObservation;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates one evolution cycle: observe, trigger, ask the advisor, validate, detect no-ops, create a candidate and
 * evaluate it. The AI never touches the active policy; activation needs {@link ApprovePolicyUseCase}.
 */
public class ProposePolicyUseCase {
    public enum Trigger { MANUAL, SCHEDULED, DETERMINISTIC }

    private final PolicyRepository policies;
    private final ExperimentRepository experiments;
    private final TrafficMetricsPort traffic;
    private final QueueRepository queue;
    private final AiPolicyAdvisorPort advisor;
    private final AiInvocationRepository invocations;
    private final OperationalMetricsPort metrics;
    private final EffectiveFlags flags;
    private final EvaluatePolicyUseCase evaluation;
    private final PromptBuilder prompts;
    private final Redactor redactor;
    private final JsonCodec json;
    private final EvolutionSettings settings;
    private final EventSupport events;
    private final Clock clock;

    public ProposePolicyUseCase(PolicyRepository policies, ExperimentRepository experiments, TrafficMetricsPort traffic,
                                QueueRepository queue, AiPolicyAdvisorPort advisor, AiInvocationRepository invocations,
                                OperationalMetricsPort metrics, EffectiveFlags flags, EvaluatePolicyUseCase evaluation,
                                PromptBuilder prompts, Redactor redactor, JsonCodec json, EvolutionSettings settings,
                                EvolutionEventPublisher publisher, Clock clock) {
        this.policies = policies;
        this.experiments = experiments;
        this.traffic = traffic;
        this.queue = queue;
        this.advisor = advisor;
        this.invocations = invocations;
        this.metrics = metrics;
        this.flags = flags;
        this.evaluation = evaluation;
        this.prompts = prompts;
        this.redactor = redactor;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
        this.events = new EventSupport(publisher, clock);
    }

    /** Scheduled entry point: respects flags and the trigger threshold, never throws. */
    public void runScheduled() {
        if (!flags.automaticEnabled() || !flags.aiEnabled() || policies.candidate().isPresent()) return;
        if (traffic.observation(settings.windowSeconds()).total() < flags.triggerArrivals()) return;
        try {
            runCycle(Trigger.SCHEDULED);
        } catch (RuntimeException ex) {
            events.emit("ERROR", "evolution", EvolutionStage.DIAGNOSTIC, null, null, null,
                    "Scheduled evolution cycle failed: " + ex.getClass().getSimpleName(), Map.of());
        }
    }

    public synchronized CycleResult runCycle(Trigger trigger) {
        if (!flags.aiEnabled()) throw new FeatureDisabledException("AI evolution disabled: enable it via the AI feature flag");
        if (policies.candidate().isPresent()) throw new ConflictException("Review the existing candidate first");
        String cycleId = UUID.randomUUID().toString();
        metrics.cycleStarted();
        TrafficPolicy baseline = policies.active();
        TrafficObservation observation = traffic.observation(settings.windowSeconds());
        TrafficMetrics snapshot = traffic.snapshot(settings.windowSeconds(), queue.depth());
        events.info("evolution", EvolutionStage.OBSERVED, cycleId, null, baseline.version(),
                "Observed " + observation.total() + " arrivals in " + settings.windowSeconds() + "s",
                Map.of("metrics", snapshot, "source", TrafficMetrics.OBSERVED));
        events.info("evolution", EvolutionStage.TRIGGERED, cycleId, null, baseline.version(),
                "Evolution cycle triggered (" + trigger + ")", Map.of("trigger", trigger.name()));
        try {
            return execute(cycleId, trigger, baseline, observation, snapshot);
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            events.emit("ERROR", "evolution", EvolutionStage.FAILED, cycleId, null, baseline.version(),
                    "Evolution cycle failed unexpectedly: " + ex.getClass().getSimpleName(), Map.of());
            metrics.cycleCompleted(EvolutionStage.FAILED.name());
            throw ex;
        }
    }

    private CycleResult execute(String cycleId, Trigger trigger, TrafficPolicy baseline, TrafficObservation observation,
                                TrafficMetrics snapshot) {
        if (observation.total() < settings.minArrivalsForCycle()) {
            String note = "Fewer than " + settings.minArrivalsForCycle() + " arrivals observed; AI not called";
            return finish(cycleId, trigger, baseline, null, EvolutionStage.INSUFFICIENT_DATA,
                    EvolutionDecisionType.INSUFFICIENT_DATA, note, null, null, null, observation.total(), note);
        }
        String invocationId = UUID.randomUUID().toString();
        String model = flags.model();
        AiPrompt prompt = prompts.build(invocationId, cycleId, baseline, snapshot, observation, settings.backendCapacity(),
                experiments.recent(settings.lessonsLimit()));
        events.info("ai", EvolutionStage.AI_CALLED, cycleId, invocationId, baseline.version(),
                "Calling model " + model, Map.of("model", model));
        AiCallResult call = advisor.advise(prompt, model);
        AiInvocation invocation = newInvocation(prompt, call, baseline);
        if (call.status() != AiInvocation.CallStatus.SUCCESS) {
            invocation = invocation.withOutcome("AI_CALL_FAILED", null, null);
            invocations.save(invocation);
            metrics.aiInvocation(invocation);
            events.emit("ERROR", "ai", EvolutionStage.FAILED, cycleId, invocationId, baseline.version(),
                    "AI call " + call.status() + ": " + call.error(), Map.of("status", call.status().name()));
            finish(cycleId, trigger, baseline, null, EvolutionStage.FAILED, null, "AI call failed: " + call.error(),
                    null, invocationId, null, observation.total(), call.error());
            throw new AiUnavailableException("AI model unavailable: " + call.error());
        }
        if (call.decision() == null) {
            var errors = List.of(call.parseError() == null ? "Unparseable AI response" : call.parseError());
            invocation = invocation.withValidation(null, AiInvocation.ValidationStatus.INVALID, errors)
                    .withOutcome("INVALID_RESPONSE", null, null);
            invocations.save(invocation);
            metrics.aiInvocation(invocation);
            metrics.validationRejected();
            events.emit("WARN", "ai", EvolutionStage.FAILED, cycleId, invocationId, baseline.version(),
                    "AI response rejected: " + errors.get(0), Map.of("errors", errors));
            return finish(cycleId, trigger, baseline, null, EvolutionStage.FAILED, null,
                    "AI response invalid: " + errors.get(0), null, invocationId, null, observation.total(), errors.get(0));
        }
        EvolutionDecision decision = call.decision();
        events.info("ai", EvolutionStage.AI_CALLED, cycleId, invocationId, baseline.version(),
                "AI answered " + decision.type() + " in " + call.durationMs() + " ms",
                Map.of("decision", decision.type().name(), "durationMs", call.durationMs()));
        switch (decision.type()) {
            case NO_CHANGE, INSUFFICIENT_DATA -> {
                invocation = invocation.withValidation(decision, AiInvocation.ValidationStatus.VALID, List.of())
                        .withOutcome(decision.type().name(), null, null);
                invocations.save(invocation);
                metrics.aiInvocation(invocation);
                EvolutionStage stage = decision.type() == EvolutionDecisionType.NO_CHANGE
                        ? EvolutionStage.NO_CHANGE : EvolutionStage.INSUFFICIENT_DATA;
                return finish(cycleId, trigger, baseline, null, stage, decision.type(), decision.rationale(), null,
                        invocationId, null, observation.total(), "AI recommended " + decision.type());
            }
            default -> {
                return handleProposal(cycleId, trigger, baseline, observation, invocation, decision);
            }
        }
    }

    private CycleResult handleProposal(String cycleId, Trigger trigger, TrafficPolicy baseline, TrafficObservation observation,
                                       AiInvocation invocation, EvolutionDecision decision) {
        String invocationId = invocation.id();
        List<String> violations = PolicyCandidate.violations(baseline, decision.admissionPerSecond(),
                decision.maxQueue(), true);
        if (!violations.isEmpty() && !"No operational policy change".equals(violations.get(0))) {
            invocation = invocation.withValidation(decision, AiInvocation.ValidationStatus.INVALID, violations)
                    .withOutcome("VALIDATION_REJECTED", null, null);
            invocations.save(invocation);
            metrics.aiInvocation(invocation);
            metrics.validationRejected();
            events.emit("WARN", "governance", EvolutionStage.REJECTED, cycleId, invocationId, baseline.version(),
                    "AI proposal outside safety envelope", Map.of("violations", violations));
            return finish(cycleId, trigger, baseline, null, EvolutionStage.REJECTED, EvolutionDecisionType.PROPOSE,
                    decision.rationale(), null, invocationId, null, observation.total(), String.join("; ", violations));
        }
        invocation = invocation.withValidation(decision, AiInvocation.ValidationStatus.VALID, List.of());
        var next = new TrafficPolicy(baseline.version() + 1, dev.demo.traffic.domain.policy.PolicyMode.EVOLVED,
                decision.admissionPerSecond(), decision.maxQueue(), baseline.simulatedWorkMs());
        if (baseline.sameBehavior(next)) {
            invocation = invocation.withOutcome("NO_OP_PROPOSAL", null, null);
            invocations.save(invocation);
            metrics.aiInvocation(invocation);
            return finish(cycleId, trigger, baseline, null, EvolutionStage.NO_CHANGE, EvolutionDecisionType.NO_CHANGE,
                    "Identical operational parameters", null, invocationId, null, observation.total(),
                    "Semantic no-op proposal discarded: " + decision.rationale());
        }
        PolicyCandidate candidate = PolicyCandidate.of(baseline, decision.admissionPerSecond(), decision.maxQueue(),
                "AI:" + invocation.model(), decision.rationale(), cycleId, clock.instant());
        policies.saveCandidate(candidate);
        events.info("evolution", EvolutionStage.PROPOSED, cycleId, invocationId, next.version(),
                "Candidate v" + next.version() + " proposed: " + next.admissionPerSecond() + "/s, queue " + next.maxQueue(),
                Map.of("admissionPerSecond", next.admissionPerSecond(), "maxQueue", next.maxQueue(),
                        "rationale", redactor.redact(decision.rationale())));
        EvaluationResult result = evaluation.evaluateAndStore(candidate, observation.arrivalsPerSecond(),
                EvaluationResult.TRACE_OBSERVED);
        invocation = invocation.withOutcome(result.valid() ? "CANDIDATE_AWAITING_APPROVAL" : "CANDIDATE_FAILED_EVALUATION",
                next.version(), json.toJson(result));
        invocations.save(invocation);
        metrics.aiInvocation(invocation);
        if (!result.valid()) {
            policies.discardCandidate();
            events.emit("WARN", "governance", EvolutionStage.REJECTED, cycleId, invocationId, next.version(),
                    "Candidate rejected by evaluation", Map.of("reasons", result.reasons()));
            return finish(cycleId, trigger, baseline, next, EvolutionStage.REJECTED, EvolutionDecisionType.PROPOSE,
                    decision.rationale(), result, invocationId, null, observation.total(), String.join("; ", result.reasons()));
        }
        return finish(cycleId, trigger, baseline, next, EvolutionStage.EVALUATED, EvolutionDecisionType.PROPOSE,
                decision.rationale(), result, invocationId, Boolean.TRUE, observation.total(),
                "Awaiting human approval");
    }

    /** Deterministic safety proposal (no model involved): 80% of the current admission rate. */
    public synchronized CycleResult proposeDeterministic() {
        if (policies.candidate().isPresent()) throw new ConflictException("Review the existing candidate first");
        String cycleId = UUID.randomUUID().toString();
        TrafficPolicy active = policies.active();
        int lowered = Math.max(1, (int) Math.floor(active.admissionPerSecond() * 0.8));
        PolicyCandidate candidate;
        try {
            candidate = PolicyCandidate.of(active, lowered, active.maxQueue(), "DETERMINISTIC",
                    "Deterministic 20% admission reduction with bounded waiting room", cycleId, clock.instant());
        } catch (IllegalArgumentException ex) {
            throw new ConflictException(ex.getMessage());
        }
        metrics.cycleStarted();
        events.info("evolution", EvolutionStage.TRIGGERED, cycleId, null, active.version(),
                "Deterministic proposal requested", Map.of("trigger", Trigger.DETERMINISTIC.name()));
        policies.saveCandidate(candidate);
        events.info("evolution", EvolutionStage.PROPOSED, cycleId, null, candidate.policy().version(),
                "Deterministic candidate v" + candidate.policy().version() + " proposed; evaluation required",
                Map.of("admissionPerSecond", lowered, "maxQueue", active.maxQueue()));
        return finish(cycleId, Trigger.DETERMINISTIC, active, candidate.policy(), EvolutionStage.PROPOSED,
                EvolutionDecisionType.PROPOSE, candidate.rationale(), null, null, null, 0, "Awaiting evaluation");
    }

    private CycleResult finish(String cycleId, Trigger trigger, TrafficPolicy baseline, TrafficPolicy candidate,
                               EvolutionStage status, EvolutionDecisionType decision, String rationale,
                               EvaluationResult result, String invocationId, Boolean awaiting, long arrivals, String note) {
        experiments.save(new EvolutionExperiment(cycleId, clock.instant(), trigger.name(), status, baseline, candidate,
                decision, rationale, result, invocationId, arrivals, note));
        boolean terminalNow = status != EvolutionStage.EVALUATED && status != EvolutionStage.PROPOSED;
        if (terminalNow && status != EvolutionStage.FAILED && status != EvolutionStage.REJECTED) {
            events.info("evolution", status, cycleId, invocationId, baseline.version(), note == null ? status.name() : note,
                    Map.of());
        }
        if (terminalNow) metrics.cycleCompleted(status.name());
        else if (status == EvolutionStage.EVALUATED) metrics.cycleCompleted("AWAITING_APPROVAL");
        return new CycleResult(cycleId, status, decision, rationale, candidate, result, invocationId,
                Boolean.TRUE.equals(awaiting), note);
    }

    private AiInvocation newInvocation(AiPrompt prompt, AiCallResult call, TrafficPolicy baseline) {
        String system = redactor.redact(prompt.systemPrompt());
        String user = redactor.redact(prompt.userPrompt());
        String payload = redactor.redact(call.requestPayload());
        boolean redacted = !system.equals(prompt.systemPrompt()) || !user.equals(prompt.userPrompt())
                || (call.requestPayload() != null && !payload.equals(call.requestPayload()));
        String raw = redactor.redact(call.rawResponse());
        redacted |= call.rawResponse() != null && !raw.equals(call.rawResponse());
        return new AiInvocation(prompt.invocationId(), prompt.cycleId(), clock.instant(), call.model(), call.modelVersion(),
                system, user, payload, call.parameters(), prompt.metricsContext(), prompt.historyContext(), raw, null,
                null, null, null, AiInvocation.ValidationStatus.NOT_APPLICABLE, List.of(), null, null, baseline.version(),
                null, call.durationMs(), call.promptTokens(), call.completionTokens(), call.status(),
                call.error() == null ? null : redactor.redact(call.error()), redacted);
    }
}
