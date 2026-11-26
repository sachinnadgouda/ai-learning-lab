package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.application.usecase.AdmitRequestUseCase;
import dev.demo.traffic.domain.traffic.AdmissionDecision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Traffic")
public class ShopController {
    public record ShopResponse(String state, String ticket, long queueDepth) {}

    private final AdmitRequestUseCase admit;
    private final ShopBackend backend;

    public ShopController(AdmitRequestUseCase admit, ShopBackend backend) {
        this.admit = admit;
        this.backend = backend;
    }

    @GetMapping("/shop")
    @Operation(summary = "Protected demo endpoint (public). 200 admitted, 202 queued/waiting, 429 rejected, 410 ticket expired")
    public ResponseEntity<?> shop(@RequestParam(required = false) String ticket) throws InterruptedException {
        var result = admit.admit(ticket);
        AdmissionDecision d = result.decision();
        var body = new ShopResponse(d.state().name(), d.ticketId(), d.queueDepth());
        return switch (d.state()) {
            case QUEUE, WAIT -> ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            case REJECT -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1").body(body);
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).body(body);
            case ADMIT -> {
                backend.handle(result.policy());
                yield ResponseEntity.ok(Map.of("result", "purchase endpoint reached", "policyVersion",
                        result.policy().version()));
            }
        };
    }
}
