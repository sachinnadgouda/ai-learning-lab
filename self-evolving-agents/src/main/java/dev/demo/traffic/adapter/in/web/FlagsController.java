package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.Requests.FlagRequest;
import dev.demo.traffic.adapter.in.web.dto.Requests.ModelRequest;
import dev.demo.traffic.adapter.in.web.dto.Requests.ThresholdRequest;
import dev.demo.traffic.application.model.FeatureFlags;
import dev.demo.traffic.application.usecase.ConfigureEvolutionUseCase;
import dev.demo.traffic.application.usecase.EffectiveFlags;
import dev.demo.traffic.application.usecase.EnableAiUseCase;
import dev.demo.traffic.application.usecase.EnableAutomaticEvolutionUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flags")
@Tag(name = "Feature flags")
public class FlagsController {
    private final EffectiveFlags flags;
    private final EnableAiUseCase ai;
    private final EnableAutomaticEvolutionUseCase automatic;
    private final ConfigureEvolutionUseCase configure;

    public FlagsController(EffectiveFlags flags, EnableAiUseCase ai, EnableAutomaticEvolutionUseCase automatic,
                           ConfigureEvolutionUseCase configure) {
        this.flags = flags;
        this.ai = ai;
        this.automatic = automatic;
        this.configure = configure;
    }

    @GetMapping
    public FeatureFlags get() {
        return flags.snapshot();
    }

    @PutMapping("/ai")
    public FeatureFlags ai(@Valid @RequestBody FlagRequest request, Authentication auth) {
        AdminAudit.log(auth, "flag.ai", String.valueOf(request.enabled()));
        return ai.setEnabled(request.enabled(), AdminAudit.actor(auth));
    }

    @PutMapping("/automatic")
    public FeatureFlags automatic(@Valid @RequestBody FlagRequest request, Authentication auth) {
        AdminAudit.log(auth, "flag.automatic", String.valueOf(request.enabled()));
        return automatic.setEnabled(request.enabled(), AdminAudit.actor(auth));
    }

    @PutMapping("/model")
    public FeatureFlags model(@Valid @RequestBody ModelRequest request, Authentication auth) {
        AdminAudit.log(auth, "flag.model", request.model());
        return configure.selectModel(request.model(), AdminAudit.actor(auth));
    }

    @PutMapping("/trigger-threshold")
    public FeatureFlags threshold(@Valid @RequestBody ThresholdRequest request, Authentication auth) {
        AdminAudit.log(auth, "flag.threshold", String.valueOf(request.arrivals()));
        return configure.setTriggerArrivals(request.arrivals(), AdminAudit.actor(auth));
    }
}
