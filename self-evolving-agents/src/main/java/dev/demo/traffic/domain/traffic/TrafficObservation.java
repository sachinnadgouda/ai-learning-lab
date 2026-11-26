package dev.demo.traffic.domain.traffic;

import java.util.List;

/** Observed arrivals per second for a recent window, oldest first. */
public record TrafficObservation(List<Integer> arrivalsPerSecond) {
    public TrafficObservation {
        arrivalsPerSecond = List.copyOf(arrivalsPerSecond);
        if (arrivalsPerSecond.stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("Negative arrivals");
    }

    public long total() {
        return arrivalsPerSecond.stream().mapToLong(Integer::longValue).sum();
    }

    public int peak() {
        return arrivalsPerSecond.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public double average() {
        return arrivalsPerSecond.isEmpty() ? 0 : (double) total() / arrivalsPerSecond.size();
    }
}
