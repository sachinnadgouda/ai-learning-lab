package dev.demo.traffic.adapter.out.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.application.model.AiInvocationFilter;
import dev.demo.traffic.application.model.EventFilter;
import dev.demo.traffic.domain.evolution.AiInvocation;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import dev.demo.traffic.domain.evolution.EvolutionStage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real Flyway migration and SQL against H2 in PostgreSQL mode. */
class JdbcRepositoriesTest {
    private JdbcAiInvocationRepository ai;
    private JdbcEvolutionEventRepository events;

    @BeforeEach void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var mapper = JsonMapper.builder().build();
        var jdbc = JdbcClient.create(ds);
        ai = new JdbcAiInvocationRepository(jdbc, mapper);
        events = new JdbcEvolutionEventRepository(jdbc, mapper);
    }

    private static AiInvocation invocation(String id, String cycle, Instant at, EvolutionDecisionType type, String user) {
        return new AiInvocation(id, cycle, at, "qwen2.5:3b", "digest", "SYS", user, "{\"payload\":1}", "{\"t\":0.2}",
                "{\"m\":1}", "[]", "RAW \"quoted\" \n response", type, 40, 500, "why", AiInvocation.ValidationStatus.VALID,
                List.of("e1"), null, "PENDING", 1, 2, 123L, 10, 5, AiInvocation.CallStatus.SUCCESS, null, true);
    }

    @Test void storesAndReturnsExactPayloads() {
        var inv = invocation("a1", "c1", Instant.parse("2025-01-01T00:00:00Z"), EvolutionDecisionType.PROPOSE, "USER PROMPT");
        ai.save(inv);
        var loaded = ai.find("a1").orElseThrow();
        assertEquals(inv.rawResponse(), loaded.rawResponse());
        assertEquals(inv.systemPrompt(), loaded.systemPrompt());
        assertEquals(inv.requestPayload(), loaded.requestPayload());
        assertEquals(List.of("e1"), loaded.validationErrors());
        assertEquals(inv.createdAt(), loaded.createdAt());
        assertTrue(loaded.redacted());
    }

    @Test void updatesOutcomeAndFiltersWithPagination() {
        var t = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            ai.save(invocation("id" + i, i < 3 ? "cA" : "cB", t.plusSeconds(i),
                    i % 2 == 0 ? EvolutionDecisionType.PROPOSE : EvolutionDecisionType.NO_CHANGE, "needle" + i));
        }
        ai.updateOutcome(ai.find("id0").orElseThrow().withOutcome("EVALUATED", 7, "{\"ok\":true}"));
        assertEquals("EVALUATED", ai.find("id0").orElseThrow().outcome());
        assertEquals(7, ai.find("id0").orElseThrow().candidateVersion());

        var page = ai.page(AiInvocationFilter.none(), 0, 2);
        assertEquals(5, page.total());
        assertEquals("id4", page.items().get(0).id(), "newest first");
        assertEquals(2, page.items().size());
        var byCycle = ai.page(new AiInvocationFilter(null, null, null, null, null, "cA", null, null), 0, 10);
        assertEquals(3, byCycle.total());
        var byDecision = ai.page(new AiInvocationFilter(null, null, "NO_CHANGE", null, null, null, null, null), 0, 10);
        assertEquals(2, byDecision.total());
        var byText = ai.page(new AiInvocationFilter(null, null, null, null, null, null, null, "needle3"), 0, 10);
        assertEquals(1, byText.total());
    }

    @Test void retentionDeletesOldRows() {
        ai.save(invocation("old", "c", Instant.parse("2020-01-01T00:00:00Z"), EvolutionDecisionType.NO_CHANGE, "u"));
        ai.save(invocation("new", "c", Instant.parse("2030-01-01T00:00:00Z"), EvolutionDecisionType.NO_CHANGE, "u"));
        assertEquals(1, ai.deleteOlderThan(Instant.parse("2025-01-01T00:00:00Z")));
        assertTrue(ai.find("old").isEmpty());
        assertTrue(ai.find("new").isPresent());
    }

    @Test void storesEventsAndBuildsTimelinePerCycle() {
        var t = Instant.parse("2025-01-01T00:00:00Z");
        events.save(new EvolutionEvent(t, "INFO", "evolution", EvolutionStage.OBSERVED, "c1", null, 1, "observed", Map.of("rps", 40)));
        events.save(new EvolutionEvent(t.plusSeconds(1), "WARN", "ai", EvolutionStage.AI_CALLED, "c1", "a1", 1, "called", Map.of()));
        events.save(new EvolutionEvent(t.plusSeconds(2), "INFO", "evolution", EvolutionStage.OBSERVED, "c2", null, 2, "other", Map.of()));
        var timeline = events.timeline("c1");
        assertEquals(List.of(EvolutionStage.OBSERVED, EvolutionStage.AI_CALLED), timeline.stream().map(EvolutionEvent::stage).toList());
        assertEquals(1, events.page(new EventFilter(null, null, "WARN", null, null, null, null, null), 0, 10).total());
        assertEquals(1, events.page(new EventFilter(null, null, null, null, "a1", null, null, null), 0, 10).total());
        assertEquals(1, events.page(new EventFilter(null, null, null, null, null, 2, null, null), 0, 10).total());
        assertEquals(3, events.deleteOlderThan(Instant.parse("2030-01-01T00:00:00Z")));
    }
}
