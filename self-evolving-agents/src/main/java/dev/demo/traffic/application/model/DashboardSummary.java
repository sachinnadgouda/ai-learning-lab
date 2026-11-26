package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import dev.demo.traffic.domain.traffic.TrafficMetrics;

public record DashboardSummary(TrafficMetrics metrics, String trafficMode, TrafficPolicy activePolicy,
                               TrafficPolicy candidatePolicy, boolean candidateEvaluated, FeatureFlags flags,
                               AiModelStatus ollama) {}
