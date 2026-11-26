package dev.demo.traffic.application.port.out;

import dev.demo.traffic.domain.traffic.AdmissionDecision;
import dev.demo.traffic.domain.traffic.TrafficMetrics;
import dev.demo.traffic.domain.traffic.TrafficObservation;
import dev.demo.traffic.domain.traffic.TrafficPoint;
import java.util.List;

public interface TrafficMetricsPort {
    void recordArrival();

    void recordDecision(AdmissionDecision decision);

    void backendStarted();

    /** Called when a backend call finishes; {@code success=false} means a 5xx. */
    void backendFinished(long latencyMs, boolean success);

    TrafficObservation observation(int seconds);

    TrafficMetrics snapshot(int windowSeconds, long queueDepth);

    /** Records a one-second sample (including queue depth) used by the time series. */
    void sample(long queueDepth);

    List<TrafficPoint> series(int seconds);
}
