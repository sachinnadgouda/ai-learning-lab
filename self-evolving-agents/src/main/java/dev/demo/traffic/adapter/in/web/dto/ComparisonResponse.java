package dev.demo.traffic.adapter.in.web.dto;

import dev.demo.traffic.application.model.AiInvocationComparison;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

public record ComparisonResponse(AiInvocationDetail left, AiInvocationDetail right,
                                 List<AiInvocationComparison.FieldDiff> differences) {
    public static ComparisonResponse of(AiInvocationComparison c, JsonMapper mapper) {
        return new ComparisonResponse(AiInvocationDetail.of(c.left(), mapper), AiInvocationDetail.of(c.right(), mapper),
                c.differences());
    }
}
