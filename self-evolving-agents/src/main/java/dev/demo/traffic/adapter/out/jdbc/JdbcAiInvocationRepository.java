package dev.demo.traffic.adapter.out.jdbc;

import dev.demo.traffic.application.model.AiInvocationFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.application.port.out.AiInvocationRepository;
import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class JdbcAiInvocationRepository implements AiInvocationRepository {
    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public JdbcAiInvocationRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(AiInvocation i) {
        jdbc.sql("DELETE FROM ai_invocation WHERE id = ?").param(i.id()).update();
        jdbc.sql("""
                INSERT INTO ai_invocation (id, cycle_id, created_at, model, model_version, system_prompt, user_prompt,
                  request_payload, parameters, metrics_context, history_context, raw_response, decision, proposed_admission,
                  proposed_max_queue, rationale, validation_status, validation_errors, evaluation, outcome,
                  policy_version_before, candidate_version, duration_ms, prompt_tokens, completion_tokens, status, error, redacted)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(i.id(), i.cycleId(), Timestamp.from(i.createdAt()), i.model(), i.modelVersion(), i.systemPrompt(),
                        i.userPrompt(), i.requestPayload(), i.parameters(), i.metricsContext(), i.historyContext(),
                        i.rawResponse(), i.decision() == null ? null : i.decision().name(), i.proposedAdmission(),
                        i.proposedMaxQueue(), i.rationale(), i.validationStatus().name(),
                        mapper.writeValueAsString(i.validationErrors()), i.evaluation(), i.outcome(),
                        i.policyVersionBefore(), i.candidateVersion(), i.durationMs(), i.promptTokens(),
                        i.completionTokens(), i.status().name(), i.error(), i.redacted())
                .update();
    }

    @Override
    public void updateOutcome(AiInvocation i) {
        jdbc.sql("UPDATE ai_invocation SET outcome = ?, evaluation = ?, candidate_version = ? WHERE id = ?")
                .params(i.outcome(), i.evaluation(), i.candidateVersion(), i.id()).update();
    }

    @Override
    public Optional<AiInvocation> find(String id) {
        return jdbc.sql("SELECT * FROM ai_invocation WHERE id = ?").param(id).query(this::map).optional();
    }

    @Override
    public Page<AiInvocation> page(AiInvocationFilter f, int page, int size) {
        var where = new StringBuilder(" WHERE 1=1");
        var params = new ArrayList<Object>();
        if (f.from() != null) { where.append(" AND created_at >= ?"); params.add(Timestamp.from(f.from())); }
        if (f.to() != null) { where.append(" AND created_at <= ?"); params.add(Timestamp.from(f.to())); }
        if (f.decision() != null) { where.append(" AND decision = ?"); params.add(f.decision()); }
        if (f.status() != null) { where.append(" AND status = ?"); params.add(f.status()); }
        if (f.validation() != null) { where.append(" AND validation_status = ?"); params.add(f.validation()); }
        if (f.cycleId() != null) { where.append(" AND cycle_id = ?"); params.add(f.cycleId()); }
        if (f.model() != null) { where.append(" AND model = ?"); params.add(f.model()); }
        if (f.text() != null && !f.text().isBlank()) {
            where.append(" AND (LOWER(COALESCE(rationale,'')) LIKE ? OR LOWER(COALESCE(raw_response,'')) LIKE ?"
                    + " OR LOWER(COALESCE(user_prompt,'')) LIKE ? OR LOWER(id) LIKE ? OR LOWER(COALESCE(cycle_id,'')) LIKE ?)");
            String like = "%" + f.text().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int k = 0; k < 5; k++) params.add(like);
        }
        Long total = jdbc.sql("SELECT COUNT(*) FROM ai_invocation" + where).params(params).query(Long.class).single();
        var queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add((long) page * size);
        List<AiInvocation> items = jdbc.sql("SELECT * FROM ai_invocation" + where
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?").params(queryParams).query(this::map).list();
        return new Page<>(items, page, size, total);
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM ai_invocation WHERE created_at < ?").param(Timestamp.from(cutoff)).update();
    }

    private AiInvocation map(ResultSet rs, int row) throws SQLException {
        String decision = rs.getString("decision");
        String errors = rs.getString("validation_errors");
        List<String> errorList = errors == null ? List.of() : mapper.readValue(errors, new TypeReference<List<String>>() { });
        return new AiInvocation(rs.getString("id"), rs.getString("cycle_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("model"), rs.getString("model_version"), rs.getString("system_prompt"),
                rs.getString("user_prompt"), rs.getString("request_payload"), rs.getString("parameters"),
                rs.getString("metrics_context"), rs.getString("history_context"), rs.getString("raw_response"),
                decision == null ? null : EvolutionDecisionType.valueOf(decision), (Integer) rs.getObject("proposed_admission"),
                (Integer) rs.getObject("proposed_max_queue"), rs.getString("rationale"),
                AiInvocation.ValidationStatus.valueOf(rs.getString("validation_status")), errorList,
                rs.getString("evaluation"), rs.getString("outcome"), (Integer) rs.getObject("policy_version_before"),
                (Integer) rs.getObject("candidate_version"), rs.getLong("duration_ms"),
                (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("completion_tokens"),
                AiInvocation.CallStatus.valueOf(rs.getString("status")), rs.getString("error"), rs.getBoolean("redacted"));
    }
}
