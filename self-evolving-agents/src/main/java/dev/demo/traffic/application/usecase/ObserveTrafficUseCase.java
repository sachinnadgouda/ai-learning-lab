package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.port.out.QueueRepository;
import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.traffic.TrafficMetrics;
import dev.demo.traffic.domain.traffic.TrafficObservation;
import dev.demo.traffic.domain.traffic.TrafficPoint;
import java.util.ArrayList;
import java.util.List;

public class ObserveTrafficUseCase {
    private final TrafficMetricsPort traffic;
    private final QueueRepository queue;

    public ObserveTrafficUseCase(TrafficMetricsPort traffic, QueueRepository queue) {
        this.traffic = traffic;
        this.queue = queue;
    }

    public TrafficMetrics snapshot(int windowSeconds) {
        return traffic.snapshot(windowSeconds, queue.depth());
    }

    public TrafficObservation observation(int seconds) {
        return traffic.observation(seconds);
    }

    public void sample() {
        traffic.sample(queue.depth());
    }

    /** Time series bucketed by {@code stepSeconds} (sums for counts, max for latency/queue). */
    public List<TrafficPoint> series(int seconds, int stepSeconds) {
        List<TrafficPoint> raw = traffic.series(seconds);
        if (stepSeconds <= 1) return raw;
        var out = new ArrayList<TrafficPoint>();
        for (int i = 0; i < raw.size(); i += stepSeconds) {
            var chunk = raw.subList(i, Math.min(raw.size(), i + stepSeconds));
            out.add(new TrafficPoint(chunk.get(chunk.size() - 1).time(),
                    chunk.stream().mapToInt(TrafficPoint::incoming).sum(),
                    chunk.stream().mapToInt(TrafficPoint::admitted).sum(),
                    chunk.stream().mapToInt(TrafficPoint::rejected).sum(),
                    chunk.stream().mapToInt(TrafficPoint::backendOk).sum(),
                    chunk.stream().mapToInt(TrafficPoint::backend5xx).sum(),
                    chunk.stream().mapToDouble(TrafficPoint::p95LatencyMs).max().orElse(0),
                    chunk.stream().mapToLong(TrafficPoint::queueDepth).max().orElse(0)));
        }
        return out;
    }
}
