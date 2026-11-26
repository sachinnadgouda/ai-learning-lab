package dev.demo.traffic.config;

import dev.demo.traffic.adapter.out.events.PersistentEvolutionEventPublisher;
import dev.demo.traffic.adapter.out.jdbc.JdbcAiInvocationRepository;
import dev.demo.traffic.adapter.out.jdbc.JdbcEvolutionEventRepository;
import dev.demo.traffic.adapter.out.metrics.MicrometerOperationalMetrics;
import dev.demo.traffic.adapter.out.metrics.RedisTrafficTelemetry;
import dev.demo.traffic.adapter.out.ollama.OllamaPolicyAdvisor;
import dev.demo.traffic.adapter.out.redis.RedisExperimentRepository;
import dev.demo.traffic.adapter.out.redis.RedisFeatureFlagRepository;
import dev.demo.traffic.adapter.out.redis.RedisPolicyRepository;
import dev.demo.traffic.adapter.out.redis.RedisQueueRepository;
import dev.demo.traffic.application.model.EvolutionSettings;
import dev.demo.traffic.application.port.out.*;
import dev.demo.traffic.application.usecase.*;
import dev.demo.traffic.domain.evolution.ReplayEvaluator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Composition root: wires adapters to ports and ports to the (framework-free) use cases. */
@Configuration
public class UseCaseConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean EvolutionSettings evolutionSettings(DemoProperties p) {
        var models = new ArrayList<>(p.ai().allowedModels());
        if (!models.contains(p.ai().model())) models.add(0, p.ai().model());
        return new EvolutionSettings(p.evolution().backendCapacity(), p.evolution().minArrivals(),
                p.evolution().triggerArrivals(), p.ai().enabled(), p.ai().model(), List.copyOf(models),
                p.evolution().lessonsLimit(), p.evolution().windowSeconds());
    }

    // --- outbound adapters ---
    @Bean PolicyRepository policyRepository(StringRedisTemplate redis, JsonMapper mapper) { return new RedisPolicyRepository(redis, mapper); }
    @Bean ExperimentRepository experimentRepository(StringRedisTemplate redis, JsonMapper mapper) { return new RedisExperimentRepository(redis, mapper); }
    @Bean FeatureFlagRepository featureFlagRepository(StringRedisTemplate redis) { return new RedisFeatureFlagRepository(redis); }
    @Bean QueueRepository queueRepository(StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${demo.queue.ticket-ttl-seconds:10}") long ticketTtl) {
        return new RedisQueueRepository(redis, ticketTtl);
    }
    @Bean TrafficMetricsPort trafficMetrics(StringRedisTemplate redis, MeterRegistry registry, Clock clock) {
        return new RedisTrafficTelemetry(redis, registry, clock);
    }
    @Bean OperationalMetricsPort operationalMetrics(MeterRegistry registry, PolicyRepository policies) {
        return new MicrometerOperationalMetrics(registry, safeActive(policies));
    }
    @Bean JdbcAiInvocationRepository aiInvocationRepository(JdbcClient jdbc, JsonMapper mapper) { return new JdbcAiInvocationRepository(jdbc, mapper); }
    @Bean JdbcEvolutionEventRepository evolutionEventRepository(JdbcClient jdbc, JsonMapper mapper) { return new JdbcEvolutionEventRepository(jdbc, mapper); }
    @Bean EvolutionEventPublisher evolutionEventPublisher(JdbcEvolutionEventRepository repository) { return new PersistentEvolutionEventPublisher(repository); }
    @Bean JsonCodec jsonCodec(JsonMapper mapper) { return mapper::writeValueAsString; }
    @Bean AiPolicyAdvisorPort aiPolicyAdvisor(JsonMapper mapper, DemoProperties p) {
        return new OllamaPolicyAdvisor(mapper, p.ai().ollamaUrl(), p.ai().timeout(), p.ai().maxRetries(),
                p.ai().temperature(), p.ai().numPredict());
    }

    @Bean Gauge queueDepthGauge(MeterRegistry registry, QueueRepository queue) {
        Gauge.builder("demo.queue.unique.tickets", queue, q -> safeDepth(q)).description("Distinct queued tickets (approximates unique waiting users)").register(registry);
        return Gauge.builder("demo.queue.depth", queue, q -> safeDepth(q)).description("Waiting-room depth").register(registry);
    }

    // --- use cases ---
    @Bean ReplayEvaluator replayEvaluator() { return new ReplayEvaluator(); }
    @Bean Redactor redactor() { return new Redactor(); }
    @Bean PromptBuilder promptBuilder(JsonCodec json) { return new PromptBuilder(json); }
    @Bean EffectiveFlags effectiveFlags(FeatureFlagRepository flags, EvolutionSettings settings) { return new EffectiveFlags(flags, settings); }
    @Bean AdmitRequestUseCase admitRequestUseCase(PolicyRepository p, QueueRepository q, TrafficMetricsPort t, Clock c) { return new AdmitRequestUseCase(p, q, t, c); }
    @Bean ObserveTrafficUseCase observeTrafficUseCase(TrafficMetricsPort t, QueueRepository q) { return new ObserveTrafficUseCase(t, q); }
    @Bean EvaluatePolicyUseCase evaluatePolicyUseCase(PolicyRepository p, TrafficMetricsPort t, OperationalMetricsPort m,
            ReplayEvaluator e, EvolutionSettings s, EvolutionEventPublisher pub, Clock c) { return new EvaluatePolicyUseCase(p, t, m, e, s, pub, c); }
    @Bean ProposePolicyUseCase proposePolicyUseCase(PolicyRepository policies, ExperimentRepository experiments,
            TrafficMetricsPort traffic, QueueRepository queue, AiPolicyAdvisorPort advisor, JdbcAiInvocationRepository invocations,
            OperationalMetricsPort metrics, EffectiveFlags flags, EvaluatePolicyUseCase evaluation, PromptBuilder prompts,
            Redactor redactor, JsonCodec json, EvolutionSettings settings, EvolutionEventPublisher publisher, Clock clock) {
        return new ProposePolicyUseCase(policies, experiments, traffic, queue, advisor, invocations, metrics, flags,
                evaluation, prompts, redactor, json, settings, publisher, clock);
    }
    @Bean ApprovePolicyUseCase approvePolicyUseCase(PolicyRepository p, ExperimentRepository e, OperationalMetricsPort m, EvolutionEventPublisher pub, Clock c) { return new ApprovePolicyUseCase(p, e, m, pub, c); }
    @Bean RejectPolicyUseCase rejectPolicyUseCase(PolicyRepository p, ExperimentRepository e, EvolutionEventPublisher pub, Clock c) { return new RejectPolicyUseCase(p, e, pub, c); }
    @Bean RollbackPolicyUseCase rollbackPolicyUseCase(PolicyRepository p, ExperimentRepository x, OperationalMetricsPort m, EvolutionEventPublisher pub, Clock c) { return new RollbackPolicyUseCase(p, x, m, pub, c); }
    @Bean EnableAiUseCase enableAiUseCase(FeatureFlagRepository r, EffectiveFlags f, EvolutionEventPublisher pub, Clock c) { return new EnableAiUseCase(r, f, pub, c); }
    @Bean EnableAutomaticEvolutionUseCase enableAutomaticEvolutionUseCase(FeatureFlagRepository r, EffectiveFlags f, EvolutionEventPublisher pub, Clock c) { return new EnableAutomaticEvolutionUseCase(r, f, pub, c); }
    @Bean ConfigureEvolutionUseCase configureEvolutionUseCase(FeatureFlagRepository r, EffectiveFlags f, EvolutionSettings s, EvolutionEventPublisher pub, Clock c) { return new ConfigureEvolutionUseCase(r, f, s, pub, c); }
    @Bean GetEvolutionHistoryUseCase getEvolutionHistoryUseCase(ExperimentRepository e, JdbcEvolutionEventRepository ev) { return new GetEvolutionHistoryUseCase(e, ev); }
    @Bean AiInvocationQueries aiInvocationQueries(JdbcAiInvocationRepository r, Clock c) { return new AiInvocationQueries(r, c); }
    @Bean GetPolicyStateUseCase getPolicyStateUseCase(PolicyRepository p) { return new GetPolicyStateUseCase(p); }
    @Bean GetDashboardUseCase getDashboardUseCase(ObserveTrafficUseCase t, GetPolicyStateUseCase s, EffectiveFlags f, AiPolicyAdvisorPort a) { return new GetDashboardUseCase(t, s, f, a); }

    private static double safeDepth(QueueRepository q) {
        try { return q.depth(); } catch (RuntimeException e) { return Double.NaN; }
    }

    private static dev.demo.traffic.domain.policy.TrafficPolicy safeActive(PolicyRepository policies) {
        try { var a = policies.active(); return a != null ? a : dev.demo.traffic.domain.policy.TrafficPolicy.defaultPolicy(); } catch (RuntimeException e) { return dev.demo.traffic.domain.policy.TrafficPolicy.defaultPolicy(); }
    }
}
