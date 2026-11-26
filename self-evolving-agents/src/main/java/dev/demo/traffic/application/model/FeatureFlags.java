package dev.demo.traffic.application.model;

/** Effective runtime flags and where each value comes from ("runtime" = persisted override, "environment" = default). */
public record FeatureFlags(boolean aiEnabled, String aiSource, boolean automaticEvolutionEnabled, String automaticSource,
                           String model, String modelSource, int triggerArrivals, String triggerSource) {}
