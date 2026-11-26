package dev.demo.traffic.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class Requests {
    private Requests() {}

    public record FlagRequest(@NotNull Boolean enabled) {}

    public record ModelRequest(@NotBlank @Size(max = 100) String model) {}

    public record ThresholdRequest(@Min(20) @Max(100000) int arrivals) {}

    public record ApproveRequest(@NotBlank @Size(max = 200) String candidateFingerprint, @Size(max = 500) String reason) {}

    public record ReasonRequest(@Size(max = 500) String reason) {}
}
