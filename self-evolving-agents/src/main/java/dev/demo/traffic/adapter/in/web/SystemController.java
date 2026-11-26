package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.application.port.out.AiPolicyAdvisorPort;
import dev.demo.traffic.application.usecase.EffectiveFlags;
import dev.demo.traffic.config.DemoProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System")
public class SystemController {
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final AiPolicyAdvisorPort advisor;
    private final EffectiveFlags flags;
    private final DemoProperties props;

    public SystemController(StringRedisTemplate redis, JdbcClient jdbc, AiPolicyAdvisorPort advisor, EffectiveFlags flags,
                            DemoProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.advisor = advisor;
        this.flags = flags;
        this.props = props;
    }

    /** Public, minimal: component up/down only. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean redisUp = probe(() -> redis.hasKey("demo:ping"));
        boolean dbUp = probe(() -> jdbc.sql("SELECT 1").query(Integer.class).single());
        boolean ollamaUp = advisor.status(flags.model()).reachable();
        var out = new LinkedHashMap<String, Object>();
        out.put("status", redisUp && dbUp ? "UP" : "DEGRADED");
        out.put("redis", redisUp ? "UP" : "DOWN");
        out.put("postgres", dbUp ? "UP" : "DOWN");
        out.put("ollama", ollamaUp ? "UP" : "DOWN");
        return out;
    }

    /** Admin-only effective runtime settings. Secrets are never included. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        var out = new LinkedHashMap<String, Object>();
        out.put("flags", flags.snapshot());
        out.put("ollamaUrl", props.ai().ollamaUrl());
        out.put("allowedModels", props.ai().allowedModels());
        out.put("ollamaTimeoutSeconds", props.ai().timeout().toSeconds());
        out.put("ollamaMaxRetries", props.ai().maxRetries());
        out.put("temperature", props.ai().temperature());
        out.put("evolutionIntervalMs", props.evolution().intervalMs());
        out.put("backendCapacityPerSecond", props.evolution().backendCapacity());
        out.put("retentionAiInvocations", props.retention().aiInvocations().toString());
        out.put("retentionEvents", props.retention().events().toString());
        out.put("persistence", Map.of(
                "flagsAndPolicies", "Redis (append-only file enabled in Docker Compose); runtime flag changes survive restarts",
                "aiInvocationsAndEvents", "PostgreSQL volume; subject to retention",
                "inProcessMetrics", "Reset on restart"));
        out.put("restartRequired", Map.of(
                "flags", false, "model", false, "triggerThreshold", false,
                "ollamaUrl", true, "timeouts", true, "evolutionIntervalMs", true, "retention", true));
        return out;
    }

    private static boolean probe(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
