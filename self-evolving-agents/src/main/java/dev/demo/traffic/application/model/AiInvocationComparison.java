package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.AiInvocation;
import java.util.List;

public record AiInvocationComparison(AiInvocation left, AiInvocation right, List<FieldDiff> differences) {
    public record FieldDiff(String field, String left, String right, boolean different) {}
}
