package dev.demo.traffic.application.port.out;

import dev.demo.traffic.application.model.EventFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import java.util.List;

public interface EvolutionEventRepository {
    Page<EvolutionEvent> page(EventFilter filter, int page, int size);

    /** Lifecycle events (not DIAGNOSTIC) of one cycle, oldest first. */
    List<EvolutionEvent> timeline(String cycleId);
}
