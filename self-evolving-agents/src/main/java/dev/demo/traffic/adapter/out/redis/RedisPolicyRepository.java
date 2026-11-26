package dev.demo.traffic.adapter.out.redis;

import dev.demo.traffic.application.port.out.PolicyRepository;
import dev.demo.traffic.domain.evolution.EvaluationResult;
import dev.demo.traffic.domain.policy.PolicyCandidate;
import dev.demo.traffic.domain.policy.TrafficPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

public class RedisPolicyRepository implements PolicyRepository {
    static final String ACTIVE = "demo:policy:active";
    static final String CANDIDATE = "demo:policy:candidate";
    static final String EVALUATION = "demo:policy:evaluation";
    static final String HISTORY = "demo:policy:history";

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;

    public RedisPolicyRepository(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public TrafficPolicy active() {
        String s = redis.opsForValue().get(ACTIVE);
        return s == null ? TrafficPolicy.defaultPolicy() : mapper.readValue(s, TrafficPolicy.class);
    }

    @Override
    public Optional<PolicyCandidate> candidate() {
        String s = redis.opsForValue().get(CANDIDATE);
        if (s == null) return Optional.empty();
        var node = mapper.readTree(s);
        if (node.has("policy")) return Optional.of(mapper.treeToValue(node, PolicyCandidate.class));
        // Pre-0.2 deployments stored the bare policy; adopt it as an unevaluated candidate.
        TrafficPolicy legacy = mapper.treeToValue(node, TrafficPolicy.class);
        return Optional.of(new PolicyCandidate(legacy, legacy.version() - 1, "LEGACY",
                "Candidate created by a previous application version; it must be evaluated before approval.", null,
                java.time.Instant.EPOCH));
    }

    @Override
    public void saveCandidate(PolicyCandidate candidate) {
        redis.opsForValue().set(CANDIDATE, mapper.writeValueAsString(candidate));
        redis.delete(EVALUATION);
    }

    @Override
    public void discardCandidate() {
        redis.delete(List.of(CANDIDATE, EVALUATION));
    }

    @Override
    public Optional<EvaluationResult> evaluation() {
        String s = redis.opsForValue().get(EVALUATION);
        return s == null ? Optional.empty() : Optional.of(mapper.readValue(s, EvaluationResult.class));
    }

    @Override
    public void saveEvaluation(EvaluationResult evaluation) {
        redis.opsForValue().set(EVALUATION, mapper.writeValueAsString(evaluation));
    }

    @Override
    public void activate(TrafficPolicy next) {
        redis.opsForList().rightPush(HISTORY, mapper.writeValueAsString(active()));
        redis.opsForValue().set(ACTIVE, mapper.writeValueAsString(next));
        discardCandidate();
    }

    @Override
    public Optional<TrafficPolicy> rollback() {
        String previous = redis.opsForList().rightPop(HISTORY);
        if (previous == null) return Optional.empty();
        TrafficPolicy policy = mapper.readValue(previous, TrafficPolicy.class);
        redis.opsForValue().set(ACTIVE, mapper.writeValueAsString(policy));
        discardCandidate();
        return Optional.of(policy);
    }

    @Override
    public List<TrafficPolicy> history() {
        List<String> raw = redis.opsForList().range(HISTORY, 0, -1);
        var out = new ArrayList<TrafficPolicy>();
        if (raw != null) for (String s : raw) out.add(0, mapper.readValue(s, TrafficPolicy.class));
        return out;
    }
}
