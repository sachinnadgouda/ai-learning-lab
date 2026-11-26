package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.model.AiModelStatus;
import dev.demo.traffic.application.model.DashboardSummary;
import dev.demo.traffic.application.model.PolicyState;
import dev.demo.traffic.application.port.out.AiPolicyAdvisorPort;

public class GetDashboardUseCase {
    private final ObserveTrafficUseCase traffic;
    private final GetPolicyStateUseCase policyState;
    private final EffectiveFlags flags;
    private final AiPolicyAdvisorPort advisor;

    public GetDashboardUseCase(ObserveTrafficUseCase traffic, GetPolicyStateUseCase policyState, EffectiveFlags flags,
                               AiPolicyAdvisorPort advisor) {
        this.traffic = traffic;
        this.policyState = policyState;
        this.flags = flags;
        this.advisor = advisor;
    }

    public DashboardSummary summary() {
        PolicyState state = policyState.state();
        AiModelStatus ollama = advisor.status(flags.model());
        String mode = state.active().usesWaitingRoom() ? "WAITING_ROOM" : "STATIC_LIMIT";
        return new DashboardSummary(traffic.snapshot(60), mode, state.active(),
                state.candidate() == null ? null : state.candidate().policy(), state.evaluation() != null,
                flags.snapshot(), ollama);
    }
}
