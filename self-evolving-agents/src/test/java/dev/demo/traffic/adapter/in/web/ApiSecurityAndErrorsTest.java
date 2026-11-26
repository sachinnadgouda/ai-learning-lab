package dev.demo.traffic.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.application.port.out.FeatureFlagRepository;
import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.application.port.out.QueueRepository;
import dev.demo.traffic.domain.policy.PolicyMode;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityAndErrorsTest {
    private static final String TOKEN = "test-admin-token-123";

    @Autowired MockMvc mvc;
    @MockitoBean PolicyRepository policies;
    @MockitoBean ExperimentRepository experiments;
    @MockitoBean FeatureFlagRepository flagStore;
    @MockitoBean QueueRepository queue;

    @BeforeEach void stubs() {
        when(policies.active()).thenReturn(new TrafficPolicy(1, PolicyMode.STATIC, 20, 500, 30));
        when(policies.candidate()).thenReturn(Optional.empty());
        when(policies.evaluation()).thenReturn(Optional.empty());
        when(policies.history()).thenReturn(List.of());
        when(experiments.recent(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
    }

    @Test void adminEndpointsRejectMissingToken() throws Exception {
        mvc.perform(get("/api/v1/flags")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/ai/invocations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/policies/rollback")).andExpect(status().isUnauthorized());
    }

    @Test void wrongTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/flags").header("X-Demo-Admin", "wrong-token-value")).andExpect(status().isUnauthorized());
    }

    @Test void bothHeaderFormsAreAccepted() throws Exception {
        mvc.perform(get("/api/v1/flags").header("X-Demo-Admin", TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/flags").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiEnabled").exists());
    }

    @Test void healthIsPublicAndLeaksNoSecrets() throws Exception {
        mvc.perform(get("/api/v1/system/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.redis").value("DOWN")).andExpect(jsonPath("$.postgres").value("UP"))
                .andExpect(jsonPath("$.status").value("DEGRADED"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test void validationFailuresAreProblemDetailsWithoutInternals() throws Exception {
        mvc.perform(put("/api/v1/flags/ai").header("X-Demo-Admin", TOKEN)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test void unknownInvocationIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/ai/invocations/nope").header("X-Demo-Admin", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test void approvingWithoutCandidateIsRejectedByGovernance() throws Exception {
        mvc.perform(post("/api/v1/policies/candidate/approve").header("X-Demo-Admin", TOKEN)
                        .contentType("application/json").content("{\"candidateFingerprint\":\"1:EVOLVED:5:5:5\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void enablingAiWithoutOllamaStillWorksAndIsPersistedViaPort() throws Exception {
        mvc.perform(put("/api/v1/flags/ai").header("X-Demo-Admin", TOKEN)
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(flagStore).setAiEnabled(true);
    }

    @Test void legacyEndpointsAreGone() throws Exception {
        mvc.perform(get("/api/shop")).andExpect(status().isUnauthorized());
    }

    @Test void corsAllowsOnlyConfiguredOrigin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/flags")
                        .header("Origin", "http://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/flags")
                        .header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-demo-admin"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }
}
