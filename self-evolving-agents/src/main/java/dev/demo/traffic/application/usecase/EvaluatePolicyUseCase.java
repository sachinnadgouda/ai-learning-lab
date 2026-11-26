package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.port.out.EvolutionEventPublisher;
import dev.demo.traffic.application.port.out.OperationalMetricsPort;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.evolution.ReplayEvaluator;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvaluatePolicyUseCase {
    private final PolicyRepository policies;
    private final TrafficMetricsPort traffic;
    private final OperationalMetricsPort metrics;
    private final ReplayEvaluator evaluator;
    private final EvolutionSettings settings;
    private final EventSupport events;
    private final Clock clock;

    public EvaluatePolicyUseCase(PolicyRepository policies, TrafficMetricsPort traffic, OperationalMetricsPort metrics,
                                 ReplayEvaluator evaluator, EvolutionSettings settings,
                                 EvolutionEventPublisher publisher, Clock clock) {
        this.policies = policies;
        this.traffic = traffic;
        this.metrics = metrics;
        this.evaluator = evaluator;
        this.settings = settings;
        this.clock = clock;
        this.events = new EventSupport(publisher, clock);
    }

    /** Evaluates the pending candidate on observed arrivals, falling back to a labelled synthetic scenario. */
    public synchronized EvaluationResult evaluatePending() {
        PolicyCandidate candidate = policies.candidate().orElseThrow(() -> new NotFoundException("No candidate"));
        List<Integer> trace = new ArrayList<>(traffic.observation(settings.windowSeconds()).arrivalsPerSecond());
        String source = EvaluationResult.TRACE_OBSERVED;
        if (trace.stream().mapToLong(Integer::longValue).sum() < settings.minArrivalsForCycle()) {
            trace = syntheticTrace();
            source = EvaluationResult.TRACE_SYNTHETIC;
        }
        return evaluateAndStore(candidate, trace, source);
    }

    public EvaluationResult evaluateAndStore(PolicyCandidate candidate, List<Integer> trace, String traceSource) {
        TrafficPolicy active = policies.active();
        EvaluationResult result = evaluator.compare(active, candidate.policy(), trace, settings.backendCapacity(),
                traceSource, clock.instant());
        policies.saveEvaluation(result);
        metrics.evaluationOutcome(result.valid());
        events.info("evaluation", EvolutionStage.EVALUATED, candidate.cycleId(), null, candidate.policy().version(),
                result.valid() ? "Candidate passed simulated evaluation" : "Candidate failed simulated evaluation",
                Map.of("valid", result.valid(), "reasons", result.reasons(), "traceSource", traceSource,
                        "simulated", true, "candidateFingerprint", candidate.fingerprint()));
        return result;
    }

    static List<Integer> syntheticTrace() {
        var trace = new ArrayList<Integer>();
        for (int i = 0; i < 60; i++) trace.add(i < 15 ? 20 : i < 45 ? 100 : 30);
        return trace;
    }
}
