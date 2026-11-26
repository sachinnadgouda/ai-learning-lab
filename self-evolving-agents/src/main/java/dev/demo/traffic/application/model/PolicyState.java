package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.util.List;

public record PolicyState(TrafficPolicy active, PolicyCandidate candidate, EvaluationResult evaluation,
                          boolean approvable, List<TrafficPolicy> history) {}
