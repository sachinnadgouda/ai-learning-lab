package dev.demo.traffic.adapter.out.metrics;

import dev.demo.traffic.application.port.out.OperationalMetricsPort;
import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** All label values come from small enums, never from ids. */
public class MicrometerOperationalMetrics implements OperationalMetricsPort {
    private final MeterRegistry registry;
    private final AtomicInteger activeVersion = new AtomicInteger(1);
    private final AtomicInteger activeAdmission = new AtomicInteger();
    private final AtomicInteger activeQueue = new AtomicInteger();

    public MicrometerOperationalMetrics(MeterRegistry registry, TrafficPolicy initial) {
        this.registry = registry;
        registry.gauge("demo.policy.active.version", activeVersion);
        registry.gauge("demo.policy.active.admission.limit", activeAdmission);
        registry.gauge("demo.policy.active.max.queue", activeQueue);
        activePolicyChanged(initial);
    }

    @Override
    public void aiInvocation(AiInvocation i) {
        String outcome = switch (i.status()) {
            case SUCCESS -> "success";
            case TIMEOUT -> "timeout";
            case FAILED -> "failure";
        };
        Counter.builder("demo.ai.invocations").tag("outcome", outcome).register(registry).increment();
        Timer.builder("demo.ai.latency").publishPercentileHistogram().register(registry)
                .record(Duration.ofMillis(Math.max(0, i.durationMs())));
        if (i.status() != AiInvocation.CallStatus.SUCCESS) {
            Counter.builder("demo.ai.failures").tag("type", outcome).register(registry).increment();
        } else if (i.decision() != null) {
            Counter.builder("demo.ai.decisions").tag("decision", i.decision().name()).register(registry).increment();
        }
        if (i.promptTokens() != null) Counter.builder("demo.ai.tokens").tag("kind", "prompt").register(registry)
                .increment(i.promptTokens());
        if (i.completionTokens() != null) Counter.builder("demo.ai.tokens").tag("kind", "completion").register(registry)
                .increment(i.completionTokens());
    }

    @Override
    public void validationRejected() {
        Counter.builder("demo.ai.validation.rejections").register(registry).increment();
    }

    @Override
    public void cycleStarted() {
        Counter.builder("demo.evolution.cycles.started").register(registry).increment();
    }

    @Override
    public void cycleCompleted(String status) {
        Counter.builder("demo.evolution.cycles.completed").tag("status", status).register(registry).increment();
    }

    @Override
    public void evaluationOutcome(boolean valid) {
        Counter.builder("demo.evolution.evaluations").tag("outcome", valid ? "passed" : "failed").register(registry).increment();
    }

    @Override
    public void policyApproved() {
        Counter.builder("demo.policy.approvals").register(registry).increment();
    }

    @Override
    public void policyRolledBack() {
        Counter.builder("demo.policy.rollbacks").register(registry).increment();
    }

    @Override
    public void activePolicyChanged(TrafficPolicy policy) {
        activeVersion.set(policy.version());
        activeAdmission.set(policy.admissionPerSecond());
        activeQueue.set(policy.maxQueue());
    }
}
