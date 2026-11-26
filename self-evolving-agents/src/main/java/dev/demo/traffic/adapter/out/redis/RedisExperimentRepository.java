package dev.demo.traffic.adapter.out.redis;

import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.domain.evolution.EvolutionExperiment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the last 100 experiments (newest first). These are the "lessons" supplied to future AI cycles. */
public class RedisExperimentRepository implements ExperimentRepository {
    static final String KEY = "demo:experiments";
    static final int MAX = 100;

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;

    public RedisExperimentRepository(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public synchronized void save(EvolutionExperiment experiment) {
        String json = mapper.writeValueAsString(experiment);
        List<String> all = redis.opsForList().range(KEY, 0, -1);
        if (all != null) {
            for (int i = 0; i < all.size(); i++) {
                if (mapper.readTree(all.get(i)).path("cycleId").asString("").equals(experiment.cycleId())) {
                    redis.opsForList().set(KEY, i, json);
                    return;
                }
            }
        }
        redis.opsForList().leftPush(KEY, json);
        redis.opsForList().trim(KEY, 0, MAX - 1);
    }

    @Override
    public List<EvolutionExperiment> recent(int limit) {
        return read(0, limit - 1);
    }

    @Override
    public Optional<EvolutionExperiment> find(String cycleId) {
        return read(0, -1).stream().filter(e -> e.cycleId().equals(cycleId)).findFirst();
    }

    @Override
    public Page<EvolutionExperiment> page(int page, int size) {
        Long total = redis.opsForList().size(KEY);
        return new Page<>(read((long) page * size, (long) page * size + size - 1), page, size, total == null ? 0 : total);
    }

    private List<EvolutionExperiment> read(long from, long to) {
        List<String> raw = redis.opsForList().range(KEY, from, to);
        var out = new ArrayList<EvolutionExperiment>();
        if (raw != null) for (String s : raw) out.add(mapper.readValue(s, EvolutionExperiment.class));
        return out;
    }
}
