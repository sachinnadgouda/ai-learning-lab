package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.adapter.in.web.dto.Requests.ApproveRequest;
import dev.demo.traffic.adapter.in.web.dto.Requests.ReasonRequest;
import dev.demo.traffic.application.model.CycleResult;
import dev.demo.traffic.application.model.PolicyState;
import dev.demo.traffic.application.usecase.ApprovePolicyUseCase;
import dev.demo.traffic.application.usecase.EvaluatePolicyUseCase;
import dev.demo.traffic.application.usecase.GetPolicyStateUseCase;
import dev.demo.traffic.application.usecase.ProposePolicyUseCase;
import dev.demo.traffic.application.usecase.RejectPolicyUseCase;
import dev.demo.traffic.application.usecase.RollbackPolicyUseCase;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.evolution.GovernanceDecision;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies")
public class PolicyController {
    private final GetPolicyStateUseCase state;
    private final ProposePolicyUseCase propose;
    private final EvaluatePolicyUseCase evaluate;
    private final ApprovePolicyUseCase approve;
    private final RejectPolicyUseCase reject;
    private final RollbackPolicyUseCase rollback;

    public PolicyController(GetPolicyStateUseCase state, ProposePolicyUseCase propose, EvaluatePolicyUseCase evaluate,
                            ApprovePolicyUseCase approve, RejectPolicyUseCase reject, RollbackPolicyUseCase rollback) {
        this.state = state;
        this.propose = propose;
        this.evaluate = evaluate;
        this.approve = approve;
        this.reject = reject;
        this.rollback = rollback;
    }

    @GetMapping
    public PolicyState get() {
        return state.state();
    }

    @PostMapping("/candidates/deterministic")
    public CycleResult proposeDeterministic(Authentication auth) {
        AdminAudit.log(auth, "policy.propose.deterministic", "");
        return propose.proposeDeterministic();
    }

    @PostMapping("/candidate/evaluate")
    public EvaluationResult evaluate(Authentication auth) {
        AdminAudit.log(auth, "policy.evaluate", "");
        return evaluate.evaluatePending();
    }

    @PostMapping("/candidate/approve")
    public TrafficPolicy approve(@Valid @RequestBody ApproveRequest request, Authentication auth) {
        AdminAudit.log(auth, "policy.approve", request.candidateFingerprint());
        return approve.approve(AdminAudit.actor(auth), request.candidateFingerprint(), request.reason());
    }

    @PostMapping("/candidate/reject")
    public GovernanceDecision reject(@Valid @RequestBody(required = false) ReasonRequest request, Authentication auth) {
        String reason = request == null ? "" : request.reason();
        AdminAudit.log(auth, "policy.reject", reason);
        return reject.reject(AdminAudit.actor(auth), reason);
    }

    @PostMapping("/rollback")
    public TrafficPolicy rollback(@Valid @RequestBody(required = false) ReasonRequest request, Authentication auth) {
        String reason = request == null ? "" : request.reason();
        AdminAudit.log(auth, "policy.rollback", reason);
        return rollback.rollback(AdminAudit.actor(auth), reason);
    }
}
