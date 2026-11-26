package dev.demo.traffic.domain.traffic;

import java.time.Instant;

/** One-second sample of observed traffic. */
public record TrafficPoint(Instant time, int incoming, int admitted, int rejected, int backendOk, int backend5xx,
                           double p95LatencyMs, long queueDepth) {}
