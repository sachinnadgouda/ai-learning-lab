package dev.demo.traffic.adapter.out.jdbc;

import dev.demo.traffic.application.model.EventFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.application.port.out.EvolutionEventRepository;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class JdbcEvolutionEventRepository implements EvolutionEventRepository {
    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public JdbcEvolutionEventRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(EvolutionEvent e) {
        jdbc.sql("""
                INSERT INTO evolution_event (event_time, level, component, stage, cycle_id, ai_invocation_id,
                  policy_version, message, data) VALUES (?,?,?,?,?,?,?,?,?)""")
                .params(Timestamp.from(e.time()), e.level(), e.component(), e.stage().name(), e.cycleId(),
                        e.aiInvocationId(), e.policyVersion(), e.message(), mapper.writeValueAsString(e.data()))
                .update();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM evolution_event WHERE event_time < ?").param(Timestamp.from(cutoff)).update();
    }

    @Override
    public Page<EvolutionEvent> page(EventFilter f, int page, int size) {
        var where = new StringBuilder(" WHERE 1=1");
        var params = new ArrayList<Object>();
        if (f.from() != null) { where.append(" AND event_time >= ?"); params.add(Timestamp.from(f.from())); }
        if (f.to() != null) { where.append(" AND event_time <= ?"); params.add(Timestamp.from(f.to())); }
        if (f.level() != null) { where.append(" AND level = ?"); params.add(f.level().toUpperCase()); }
        if (f.cycleId() != null) { where.append(" AND cycle_id = ?"); params.add(f.cycleId()); }
        if (f.aiInvocationId() != null) { where.append(" AND ai_invocation_id = ?"); params.add(f.aiInvocationId()); }
        if (f.policyVersion() != null) { where.append(" AND policy_version = ?"); params.add(f.policyVersion()); }
        if (f.component() != null) { where.append(" AND component = ?"); params.add(f.component()); }
        if (f.text() != null && !f.text().isBlank()) {
            where.append(" AND LOWER(message) LIKE ?");
            params.add("%" + f.text().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.sql("SELECT COUNT(*) FROM evolution_event" + where).params(params).query(Long.class).single();
        var queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add((long) page * size);
        List<EvolutionEvent> items = jdbc.sql("SELECT * FROM evolution_event" + where
                + " ORDER BY event_time DESC, id DESC LIMIT ? OFFSET ?").params(queryParams).query(this::map).list();
        return new Page<>(items, page, size, total);
    }

    @Override
    public List<EvolutionEvent> timeline(String cycleId) {
        return jdbc.sql("SELECT * FROM evolution_event WHERE cycle_id = ? AND stage <> 'DIAGNOSTIC' ORDER BY event_time, id")
                .param(cycleId).query(this::map).list();
    }

    private EvolutionEvent map(ResultSet rs, int row) throws SQLException {
        String data = rs.getString("data");
        Map<String, Object> parsed = data == null ? Map.of()
                : mapper.readValue(data, new TypeReference<Map<String, Object>>() { });
        return new EvolutionEvent(rs.getTimestamp("event_time").toInstant(), rs.getString("level"),
                rs.getString("component"), EvolutionStage.valueOf(rs.getString("stage")), rs.getString("cycle_id"),
                rs.getString("ai_invocation_id"), (Integer) rs.getObject("policy_version"), rs.getString("message"), parsed);
    }
}
