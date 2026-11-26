package dev.demo.traffic.adapter.out.redis;

import dev.demo.traffic.application.port.out.FeatureFlagRepository;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisFeatureFlagRepository implements FeatureFlagRepository {
    static final String AI = "demo:ai:enabled";
    static final String AUTO = "demo:evolution:auto";
    static final String MODEL = "demo:ai:model";
    static final String TRIGGER = "demo:evolution:trigger";

    private final StringRedisTemplate redis;

    public RedisFeatureFlagRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Boolean> aiEnabled() {
        return bool(AI);
    }

    @Override
    public void setAiEnabled(boolean enabled) {
        redis.opsForValue().set(AI, Boolean.toString(enabled));
    }

    @Override
    public Optional<Boolean> automaticEvolution() {
        return bool(AUTO);
    }

    @Override
    public void setAutomaticEvolution(boolean enabled) {
        redis.opsForValue().set(AUTO, Boolean.toString(enabled));
    }

    @Override
    public Optional<String> model() {
        return Optional.ofNullable(redis.opsForValue().get(MODEL));
    }

    @Override
    public void setModel(String model) {
        redis.opsForValue().set(MODEL, model);
    }

    @Override
    public Optional<Integer> triggerArrivals() {
        String v = redis.opsForValue().get(TRIGGER);
        return v == null ? Optional.empty() : Optional.of(Integer.parseInt(v));
    }

    @Override
    public void setTriggerArrivals(int arrivals) {
        redis.opsForValue().set(TRIGGER, Integer.toString(arrivals));
    }

    private Optional<Boolean> bool(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(v));
    }
}
