package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.PageResponse;
import dev.demo.traffic.application.model.EventFilter;
import dev.demo.traffic.application.usecase.GetEvolutionHistoryUseCase;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only view of curated application/evolution events. It never exposes raw log files. */
@RestController
@RequestMapping("/api/v1/diagnostics")
@Tag(name = "Diagnostics")
public class DiagnosticsController {
    private final GetEvolutionHistoryUseCase history;

    public DiagnosticsController(GetEvolutionHistoryUseCase history) {
        this.history = history;
    }

    @GetMapping("/events")
    public PageResponse<EvolutionEvent> events(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
                                               @RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to,
                                               @RequestParam(required = false) String level,
                                               @RequestParam(required = false) String cycleId,
                                               @RequestParam(required = false) String aiInvocationId,
                                               @RequestParam(required = false) Integer policyVersion,
                                               @RequestParam(required = false) String component,
                                               @RequestParam(required = false, name = "q") String text) {
        var filter = new EventFilter(from, to, nullIfBlank(level), nullIfBlank(cycleId), nullIfBlank(aiInvocationId),
                policyVersion, nullIfBlank(component), nullIfBlank(text));
        return PageResponse.of(history.events(filter, page, size), e -> e);
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
