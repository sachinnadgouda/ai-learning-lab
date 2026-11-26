package dev.demo.traffic.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "demo")
public record DemoProperties(String adminToken, Ai ai, Evolution evolution, Cors cors, Retention retention) {
    public record Ai(@DefaultValue("false") boolean enabled, @DefaultValue("http://ollama:11434") String ollamaUrl,
                     @DefaultValue("qwen2.5:3b") String model, @DefaultValue("qwen2.5:3b") List<String> allowedModels,
                     @DefaultValue("90s") Duration timeout, @DefaultValue("1") int maxRetries,
                     @DefaultValue("0.2") double temperature, @DefaultValue("300") int numPredict) {}

    public record Evolution(@DefaultValue("60000") long intervalMs, @DefaultValue("50") int backendCapacity,
                            @DefaultValue("20") int minArrivals, @DefaultValue("120") int triggerArrivals,
                            @DefaultValue("60") int windowSeconds, @DefaultValue("5") int lessonsLimit) {}

    public record Cors(@DefaultValue("http://localhost:4200,http://localhost:8081") List<String> allowedOrigins) {}

    public record Retention(@DefaultValue("30d") Duration aiInvocations, @DefaultValue("30d") Duration events,
                            @DefaultValue("1h") Duration purgeInterval) {}
}
