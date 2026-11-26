package dev.demo.traffic.adapter.in.web.dto;

import dev.demo.traffic.domain.traffic.TrafficPoint;
import java.util.List;

/** Counts are per bucket of {@code stepSeconds}; divide by it for per-second rates. All values are measured in-process. */
public record TimeseriesResponse(int seconds, int stepSeconds, String source, List<TrafficPoint> points) {}
