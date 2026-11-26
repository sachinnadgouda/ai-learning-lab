package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import dev.demo.traffic.domain.policy.TrafficPolicy;

public record CycleResult(String cycleId, EvolutionStage status, EvolutionDecisionType decision, String rationale,
                          TrafficPolicy candidate, EvaluationResult evaluation, String aiInvocationId,
                          boolean awaitingApproval, String note) {}
