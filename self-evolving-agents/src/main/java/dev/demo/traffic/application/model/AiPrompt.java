package dev.demo.traffic.application.model;

/** Everything sent to the model. {@code metricsContext} and {@code historyContext} are the JSON blocks embedded in the user prompt. */
public record AiPrompt(String invocationId, String cycleId, String systemPrompt, String userPrompt,
                       String metricsContext, String historyContext) {}
