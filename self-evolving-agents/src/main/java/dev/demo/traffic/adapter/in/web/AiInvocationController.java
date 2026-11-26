package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.AiInvocationDetail;
import dev.demo.traffic.adapter.in.web.dto.ComparisonResponse;
import dev.demo.traffic.adapter.in.web.dto.PageResponse;
import dev.demo.traffic.application.model.AiInvocationFilter;
import dev.demo.traffic.application.model.AiInvocationSummary;
import dev.demo.traffic.application.model.AiModelStatus;
import dev.demo.traffic.application.usecase.AiInvocationQueries;
import dev.demo.traffic.application.usecase.EffectiveFlags;
import dev.demo.traffic.application.port.out.AiPolicyAdvisorPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Admin-only: exposes the exact prompts and responses recorded for each model call. */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI Inspector")
public class AiInvocationController {
    private final AiInvocationQueries queries;
    private final AiPolicyAdvisorPort advisor;
    private final EffectiveFlags flags;
    private final JsonMapper mapper;

    public AiInvocationController(AiInvocationQueries queries, AiPolicyAdvisorPort advisor, EffectiveFlags flags,
                                  JsonMapper mapper) {
        this.queries = queries;
        this.advisor = advisor;
        this.flags = flags;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AiModelStatus ollama = advisor.status(flags.model());
        return Map.of("flags", flags.snapshot(), "ollama", ollama, "approvalRequired", true,
                "mode", "advisory-only: proposals need evaluation and human approval");
    }

    @GetMapping("/invocations")
    public PageResponse<AiInvocationSummary> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                  @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to,
                                                  @RequestParam(required = false) String decision,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String validation,
                                                  @RequestParam(required = false) String cycleId,
                                                  @RequestParam(required = false) String model,
                                                  @RequestParam(required = false, name = "q") String text) {
        var filter = new AiInvocationFilter(from, to, blankToNull(decision), blankToNull(status), blankToNull(validation),
                blankToNull(cycleId), blankToNull(model), blankToNull(text));
        return PageResponse.of(queries.page(filter, page, size), AiInvocationSummary::of);
    }

    @GetMapping("/invocations/compare")
    public ComparisonResponse compare(@RequestParam String left, @RequestParam String right) {
        return ComparisonResponse.of(queries.compare(left, right), mapper);
    }

    @GetMapping("/invocations/{id}")
    public AiInvocationDetail get(@PathVariable String id) {
        return AiInvocationDetail.of(queries.get(id), mapper);
    }

    @GetMapping("/invocations/{id}/prompt")
    public AiInvocationDetail.Prompt prompt(@PathVariable String id) {
        return AiInvocationDetail.of(queries.get(id), mapper).prompt();
    }

    @GetMapping("/invocations/{id}/response")
    public Map<String, Object> response(@PathVariable String id) {
        var d = AiInvocationDetail.of(queries.get(id), mapper);
        return Map.of("rawResponse", d.rawResponse() == null ? "" : d.rawResponse(), "aiRecommendation", d.aiRecommendation(),
                "independentValidation", d.independentValidation(), "timing", d.timing(), "callStatus", d.callStatus());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
