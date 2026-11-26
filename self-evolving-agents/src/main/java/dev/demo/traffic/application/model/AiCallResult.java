package dev.demo.traffic.application.model;

import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.evolution.EvolutionDecision;

/** Raw outcome of one model call. {@code decision} is null when the call failed or the answer could not be parsed. */
public record AiCallResult(String model, String modelVersion, String requestPayload, String parameters,
                           String rawResponse, EvolutionDecision decision, String parseError, long durationMs,
                           Integer promptTokens, Integer completionTokens, AiInvocation.CallStatus status, String error) {}
