package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.PageResponse;
import dev.demo.traffic.application.model.CycleResult;
import dev.demo.traffic.application.usecase.GetEvolutionHistoryUseCase;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import dev.demo.traffic.domain.evolution.EvolutionExperiment;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evolution")
@Tag(name = "Evolution")
public class EvolutionController {
    private final GetEvolutionHistoryUseCase history;
    private final ProposePolicyUseCase propose;

    public EvolutionController(GetEvolutionHistoryUseCase history, ProposePolicyUseCase propose) {
        this.history = history;
        this.propose = propose;
    }

    @GetMapping("/cycles")
    public PageResponse<EvolutionExperiment> cycles(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(history.cycles(page, size), e -> e);
    }

    @GetMapping("/cycles/{cycleId}")
    public EvolutionExperiment cycle(@PathVariable String cycleId) {
        return history.cycle(cycleId);
    }

    @GetMapping("/cycles/{cycleId}/timeline")
    public Map<String, Object> timeline(@PathVariable String cycleId) {
        List<EvolutionEvent> events = history.timeline(cycleId);
        return Map.of("cycleId", cycleId, "events", events);
    }

    @PostMapping("/cycles")
    public CycleResult run(Authentication auth) {
        AdminAudit.log(auth, "evolution.cycle.run", "manual");
        return propose.runCycle(ProposePolicyUseCase.Trigger.MANUAL);
    }
}
