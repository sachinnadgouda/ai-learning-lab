package dev.demo.traffic.application.port.out;

import dev.demo.traffic.application.model.AiInvocationFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.domain.evolution.AiInvocation;
import java.time.Instant;
import java.util.Optional;

public interface AiInvocationRepository {
    void save(AiInvocation invocation);

    /** Updates the mutable result fields (outcome, evaluation, candidate version) of an existing record. */
    void updateOutcome(AiInvocation invocation);

    Optional<AiInvocation> find(String id);

    Page<AiInvocation> page(AiInvocationFilter filter, int page, int size);

    int deleteOlderThan(Instant cutoff);
}
