package dev.demo.traffic.application.support;

import dev.demo.traffic.application.model.*;
import dev.demo.traffic.application.port.out.*;
import dev.demo.traffic.domain.evolution.*;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory test doubles for every outbound port. */
public final class Fakes {
    private Fakes() {}

    public static class Policies implements PolicyRepository {
        public TrafficPolicy active = TrafficPolicy.defaultPolicy();
        public PolicyCandidate candidate;
        public EvaluationResult evaluation;
        public final Deque<TrafficPolicy> history = new ArrayDeque<>();

        @Override public TrafficPolicy active() { return active; }
        @Override public Optional<PolicyCandidate> candidate() { return Optional.ofNullable(candidate); }
        @Override public void saveCandidate(PolicyCandidate c) { candidate = c; evaluation = null; }
        @Override public void discardCandidate() { candidate = null; evaluation = null; }
        @Override public Optional<EvaluationResult> evaluation() { return Optional.ofNullable(evaluation); }
        @Override public void saveEvaluation(EvaluationResult e) { evaluation = e; }
        @Override public void activate(TrafficPolicy next) { history.push(active); active = next; discardCandidate(); }
        @Override public Optional<TrafficPolicy> rollback() {
            if (history.isEmpty()) return Optional.empty();
            active = history.pop(); discardCandidate(); return Optional.of(active);
        }
        @Override public List<TrafficPolicy> history() { return List.copyOf(history); }
    }

    public static class Experiments implements ExperimentRepository {
        public final List<EvolutionExperiment> items = new ArrayList<>();
        @Override public void save(EvolutionExperiment e) {
            items.removeIf(x -> x.cycleId().equals(e.cycleId()));
            items.add(0, e);
        }
        @Override public List<EvolutionExperiment> recent(int limit) { return items.subList(0, Math.min(limit, items.size())); }
        @Override public Optional<EvolutionExperiment> find(String id) { return items.stream().filter(e -> e.cycleId().equals(id)).findFirst(); }
        @Override public Page<EvolutionExperiment> page(int page, int size) { return new Page<>(items, page, size, items.size()); }
    }

    public static class Traffic implements TrafficMetricsPort {
        public List<Integer> arrivals = Collections.nCopies(60, 0);
        public final AtomicInteger arrivalsRecorded = new AtomicInteger();
        public final List<AdmissionDecision> decisions = new ArrayList<>();
        @Override public void recordArrival() { arrivalsRecorded.incrementAndGet(); }
        @Override public void recordDecision(AdmissionDecision d) { decisions.add(d); }
        @Override public void backendStarted() { }
        @Override public void backendFinished(long latencyMs, boolean success) { }
        @Override public TrafficObservation observation(int seconds) { return new TrafficObservation(arrivals); }
        @Override public TrafficMetrics snapshot(int window, long depth) {
            return new TrafficMetrics(Instant.EPOCH, window, 50, 40, 10, 40, 30, 35, 40, 0, 2, depth, 0, TrafficMetrics.OBSERVED);
        }
        @Override public void sample(long queueDepth) { }
        @Override public List<TrafficPoint> series(int seconds) { return List.of(); }
    }

    public static class Queue implements QueueRepository {
        public AdmissionDecision next = new AdmissionDecision(AdmissionState.ADMIT, null, 0, 0, 0);
        public String lastTicket = "unset";
        @Override public AdmissionDecision admit(TrafficPolicy p, String ticket, String newTicket, long now) { lastTicket = ticket; return next; }
        @Override public long depth() { return 3; }
    }

    public static class Flags implements FeatureFlagRepository {
        public Boolean ai, auto; public String model; public Integer trigger;
        @Override public Optional<Boolean> aiEnabled() { return Optional.ofNullable(ai); }
        @Override public void setAiEnabled(boolean e) { ai = e; }
        @Override public Optional<Boolean> automaticEvolution() { return Optional.ofNullable(auto); }
        @Override public void setAutomaticEvolution(boolean e) { auto = e; }
        @Override public Optional<String> model() { return Optional.ofNullable(model); }
        @Override public void setModel(String m) { model = m; }
        @Override public Optional<Integer> triggerArrivals() { return Optional.ofNullable(trigger); }
        @Override public void setTriggerArrivals(int a) { trigger = a; }
    }

    public static class Events implements EvolutionEventPublisher {
        public final List<EvolutionEvent> events = new ArrayList<>();
        @Override public void publish(EvolutionEvent e) { events.add(e); }
        public List<EvolutionStage> stages() { return events.stream().map(EvolutionEvent::stage).toList(); }
    }

    public static class Invocations implements AiInvocationRepository {
        public final Map<String, AiInvocation> items = new LinkedHashMap<>();
        @Override public void save(AiInvocation i) { items.put(i.id(), i); }
        @Override public void updateOutcome(AiInvocation i) { items.put(i.id(), i); }
        @Override public Optional<AiInvocation> find(String id) { return Optional.ofNullable(items.get(id)); }
        @Override public Page<AiInvocation> page(AiInvocationFilter f, int page, int size) {
            return new Page<>(new ArrayList<>(items.values()), page, size, items.size());
        }
        @Override public int deleteOlderThan(Instant cutoff) {
            int before = items.size();
            items.values().removeIf(i -> i.createdAt().isBefore(cutoff));
            return before - items.size();
        }
    }

    public static class Metrics implements OperationalMetricsPort {
        public final List<String> calls = new ArrayList<>();
        @Override public void aiInvocation(AiInvocation i) { calls.add("ai:" + i.status()); }
        @Override public void validationRejected() { calls.add("validationRejected"); }
        @Override public void cycleStarted() { calls.add("cycleStarted"); }
        @Override public void cycleCompleted(String s) { calls.add("cycleCompleted:" + s); }
        @Override public void evaluationOutcome(boolean v) { calls.add("evaluation:" + v); }
        @Override public void policyApproved() { calls.add("approved"); }
        @Override public void policyRolledBack() { calls.add("rolledBack"); }
        @Override public void activePolicyChanged(TrafficPolicy p) { calls.add("active:" + p.version()); }
    }

    /** Scripted advisor: returns queued results in order and records every prompt it received. */
    public static class Advisor implements AiPolicyAdvisorPort {
        public final Deque<AiCallResult> script = new ArrayDeque<>();
        public final List<AiPrompt> prompts = new ArrayList<>();
        public boolean reachable = true;

        public void willReturn(EvolutionDecision d) {
            script.add(new AiCallResult("test-model", "digest1", "{\"request\":true}", "{\"temperature\":0.2}",
                    "{\"message\":{\"content\":\"raw\"}}", d, null, 12, 100, 20, AiInvocation.CallStatus.SUCCESS, null));
        }
        public void willReturnUnparseable(String error) {
            script.add(new AiCallResult("test-model", null, "{}", "{}", "not json", null, error, 5, null, null,
                    AiInvocation.CallStatus.SUCCESS, null));
        }
        public void willFail(AiInvocation.CallStatus status, String error) {
            script.add(new AiCallResult("test-model", null, "{}", "{}", null, null, null, 7, null, null, status, error));
        }
        @Override public AiCallResult advise(AiPrompt prompt, String model) { prompts.add(prompt); return script.removeFirst(); }
        @Override public AiModelStatus status(String m) { return new AiModelStatus(reachable, "http://x", List.of(m), true, "ok"); }
    }

    public static class Json implements JsonCodec {
        @Override public String toJson(Object value) { return String.valueOf(value); }
    }
}
