package dev.demo.traffic.adapter.out.metrics;

import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import dev.demo.traffic.domain.traffic.AdmissionState;
import dev.demo.traffic.domain.traffic.TrafficMetrics;
import dev.demo.traffic.domain.traffic.TrafficObservation;
import dev.demo.traffic.domain.traffic.TrafficPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Arrival counts live in Redis (shared with the evolution engine); everything else is measured in this process
 * (per-second buckets, 15 minutes) and exported through Micrometer. In-process figures reset on restart.
 */
public class RedisTrafficTelemetry implements TrafficMetricsPort {
    private static final int RETENTION_SECONDS = 900;
    private static final int MAX_SAMPLES_PER_BUCKET = 2000;

    private static final class Bucket {
        int incoming, admitted, rejected, ok, errors;
        long queueDepth = -1;
        final List<Long> latencies = new ArrayList<>();
        final List<Long> waits = new ArrayList<>();
    }

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger concurrency = new AtomicInteger();
    private final Counter incomingCounter;
    private final Timer backendLatency;
    private final Timer queueWait;
    private final Counter expiredTickets;

    public RedisTrafficTelemetry(StringRedisTemplate redis, MeterRegistry registry, Clock clock) {
        this.redis = redis;
        this.registry = registry;
        this.clock = clock;
        this.incomingCounter = Counter.builder("demo.requests.incoming").description("Arrivals (new requests, not ticket polls)")
                .register(registry);
        this.backendLatency = Timer.builder("demo.backend.latency").description("Measured latency of the simulated backend")
                .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram().register(registry);
        this.queueWait = Timer.builder("demo.queue.wait").description("Measured wait of admitted queue tickets")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
        this.expiredTickets = Counter.builder("demo.queue.ticket.expired")
                .description("Expired tickets purged from the queue (approximates abandonment)").register(registry);
        registry.gauge("demo.backend.concurrent", concurrency);
    }

    private Bucket bucket() {
        long sec = clock.instant().getEpochSecond();
        Bucket b = buckets.computeIfAbsent(sec, k -> new Bucket());
        if (buckets.size() > RETENTION_SECONDS + 5) buckets.keySet().removeIf(k -> k < sec - RETENTION_SECONDS);
        return b;
    }

    @Override
    public void recordArrival() {
        long second = clock.instant().getEpochSecond();
        String key = "demo:arrivals:" + second;
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(2));
        incomingCounter.increment();
        Bucket b = bucket();
        synchronized (b) { b.incoming++; }
    }

    @Override
    public void recordDecision(AdmissionDecision decision) {
        Counter.builder("demo.admission").tag("state", decision.state().name()).register(registry).increment();
        if (decision.expiredPurged() > 0) expiredTickets.increment(decision.expiredPurged());
        Bucket b = bucket();
        synchronized (b) {
            switch (decision.state()) {
                case ADMIT -> {
                    b.admitted++;
                    if (decision.waitedMs() > 0 && b.waits.size() < MAX_SAMPLES_PER_BUCKET) b.waits.add(decision.waitedMs());
                }
                case REJECT -> b.rejected++;
                default -> { }
            }
        }
        if (decision.state() == AdmissionState.ADMIT && decision.waitedMs() > 0) {
            queueWait.record(Duration.ofMillis(decision.waitedMs()));
            Counter.builder("demo.queue.admitted").register(registry).increment();
        }
    }

    @Override
    public void backendStarted() {
        concurrency.incrementAndGet();
    }

    @Override
    public void backendFinished(long latencyMs, boolean success) {
        concurrency.decrementAndGet();
        backendLatency.record(Duration.ofMillis(latencyMs));
        Counter.builder("demo.backend.requests").tag("outcome", success ? "success" : "error").register(registry).increment();
        Bucket b = bucket();
        synchronized (b) {
            if (success) b.ok++; else b.errors++;
            if (b.latencies.size() < MAX_SAMPLES_PER_BUCKET) b.latencies.add(latencyMs);
        }
    }

    @Override
    public TrafficObservation observation(int seconds) {
        long now = clock.instant().getEpochSecond();
        var keys = new ArrayList<String>();
        for (long s = now - seconds; s < now; s++) keys.add("demo:arrivals:" + s);
        List<String> values = redis.opsForValue().multiGet(keys);
        var result = new ArrayList<Integer>();
        for (int i = 0; i < keys.size(); i++) {
            String v = values == null ? null : values.get(i);
            result.add(v == null ? 0 : Integer.parseInt(v));
        }
        return new TrafficObservation(result);
    }

    @Override
    public void sample(long queueDepth) {
        Bucket b = bucket();
        synchronized (b) { b.queueDepth = Math.max(b.queueDepth, queueDepth); }
    }

    @Override
    public TrafficMetrics snapshot(int windowSeconds, long queueDepth) {
        long now = clock.instant().getEpochSecond();
        long incoming = 0, admitted = 0, rejected = 0, ok = 0, errors = 0;
        var latencies = new ArrayList<Long>();
        var waits = new ArrayList<Long>();
        for (long s = now - windowSeconds; s < now; s++) {
            Bucket b = buckets.get(s);
            if (b == null) continue;
            synchronized (b) {
                incoming += b.incoming; admitted += b.admitted; rejected += b.rejected; ok += b.ok; errors += b.errors;
                latencies.addAll(b.latencies);
                waits.addAll(b.waits);
            }
        }
        double w = windowSeconds;
        double errorRate = ok + errors == 0 ? 0 : (double) errors / (ok + errors);
        return new TrafficMetrics(clock.instant(), windowSeconds, incoming / w, admitted / w, rejected / w, ok / w,
                percentile(latencies, 0.5), percentile(latencies, 0.95), percentile(latencies, 0.99), errorRate,
                concurrency.get(), queueDepth, percentile(waits, 0.95) / 1000.0, TrafficMetrics.OBSERVED);
    }

    @Override
    public List<TrafficPoint> series(int seconds) {
        long now = clock.instant().getEpochSecond();
        var points = new ArrayList<TrafficPoint>();
        for (long s = now - seconds; s < now; s++) {
            Bucket b = buckets.get(s);
            if (b == null) {
                points.add(new TrafficPoint(Instant.ofEpochSecond(s), 0, 0, 0, 0, 0, 0, 0));
                continue;
            }
            synchronized (b) {
                points.add(new TrafficPoint(Instant.ofEpochSecond(s), b.incoming, b.admitted, b.rejected, b.ok, b.errors,
                        percentile(b.latencies, 0.95), Math.max(0, b.queueDepth)));
            }
        }
        return points;
    }

    static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }
}
