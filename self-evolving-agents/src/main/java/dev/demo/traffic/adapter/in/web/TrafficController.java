package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.TimeseriesResponse;
import dev.demo.traffic.application.model.DashboardSummary;
import dev.demo.traffic.application.usecase.GetDashboardUseCase;
import dev.demo.traffic.application.usecase.ObserveTrafficUseCase;
import dev.demo.traffic.domain.traffic.TrafficMetrics;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Dashboard")
public class TrafficController {
    private final GetDashboardUseCase dashboard;
    private final ObserveTrafficUseCase traffic;

    public TrafficController(GetDashboardUseCase dashboard, ObserveTrafficUseCase traffic) {
        this.dashboard = dashboard;
        this.traffic = traffic;
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummary summary() {
        return dashboard.summary();
    }

    @GetMapping("/traffic/metrics")
    public TrafficMetrics metrics(@RequestParam(defaultValue = "60") @Min(5) @Max(900) int windowSeconds) {
        return traffic.snapshot(windowSeconds);
    }

    @GetMapping("/traffic/timeseries")
    public TimeseriesResponse timeseries(@RequestParam(defaultValue = "300") @Min(10) @Max(900) int seconds,
                                         @RequestParam(defaultValue = "5") @Min(1) @Max(60) int stepSeconds) {
        return new TimeseriesResponse(seconds, stepSeconds, TrafficMetrics.OBSERVED, traffic.series(seconds, stepSeconds));
    }
}
