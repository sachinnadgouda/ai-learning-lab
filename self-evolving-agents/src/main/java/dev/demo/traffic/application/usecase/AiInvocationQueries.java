package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.model.AiInvocationComparison;
import dev.demo.traffic.application.model.AiInvocationFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.application.port.out.AiInvocationRepository;
import dev.demo.traffic.domain.evolution.AiInvocation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

public class AiInvocationQueries {
    private final AiInvocationRepository repository;
    private final Clock clock;

    public AiInvocationQueries(AiInvocationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Page<AiInvocation> page(AiInvocationFilter filter, int page, int size) {
        return repository.page(filter, page, size);
    }

    public AiInvocation get(String id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Unknown AI invocation"));
    }

    public AiInvocationComparison compare(String leftId, String rightId) {
        AiInvocation l = get(leftId), r = get(rightId);
        var diffs = new ArrayList<AiInvocationComparison.FieldDiff>();
        add(diffs, "model", l.model(), r.model());
        add(diffs, "modelVersion", l.modelVersion(), r.modelVersion());
        add(diffs, "systemPrompt", l.systemPrompt(), r.systemPrompt());
        add(diffs, "userPrompt", l.userPrompt(), r.userPrompt());
        add(diffs, "parameters", l.parameters(), r.parameters());
        add(diffs, "metricsContext", l.metricsContext(), r.metricsContext());
        add(diffs, "historyContext", l.historyContext(), r.historyContext());
        add(diffs, "rawResponse", l.rawResponse(), r.rawResponse());
        add(diffs, "decision", l.decision(), r.decision());
        add(diffs, "proposedAdmission", l.proposedAdmission(), r.proposedAdmission());
        add(diffs, "proposedMaxQueue", l.proposedMaxQueue(), r.proposedMaxQueue());
        add(diffs, "rationale", l.rationale(), r.rationale());
        add(diffs, "validationStatus", l.validationStatus(), r.validationStatus());
        add(diffs, "outcome", l.outcome(), r.outcome());
        add(diffs, "durationMs", l.durationMs(), r.durationMs());
        return new AiInvocationComparison(l, r, diffs);
    }

    /** Deletes invocation records older than the retention period; returns the number removed. */
    public int purge(java.time.Duration retention) {
        Instant cutoff = clock.instant().minus(retention);
        return repository.deleteOlderThan(cutoff);
    }

    private static void add(java.util.List<AiInvocationComparison.FieldDiff> diffs, String field, Object l, Object r) {
        diffs.add(new AiInvocationComparison.FieldDiff(field, l == null ? null : l.toString(),
                r == null ? null : r.toString(), !Objects.equals(l, r)));
    }
}
