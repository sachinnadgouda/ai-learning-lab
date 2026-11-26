package dev.demo.traffic.application.usecase;

import dev.demo.traffic.application.exception.NotFoundException;
import dev.demo.traffic.application.model.EventFilter;
import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.application.port.out.EvolutionEventRepository;
import dev.demo.traffic.application.port.out.ExperimentRepository;
import dev.demo.traffic.domain.evolution.EvolutionEvent;
import dev.demo.traffic.domain.evolution.EvolutionExperiment;
import java.util.List;

public class GetEvolutionHistoryUseCase {
    private final ExperimentRepository experiments;
    private final EvolutionEventRepository events;

    public GetEvolutionHistoryUseCase(ExperimentRepository experiments, EvolutionEventRepository events) {
        this.experiments = experiments;
        this.events = events;
    }

    public Page<EvolutionExperiment> cycles(int page, int size) {
        return experiments.page(page, size);
    }

    public EvolutionExperiment cycle(String cycleId) {
        return experiments.find(cycleId).orElseThrow(() -> new NotFoundException("Unknown evolution cycle"));
    }

    public List<EvolutionEvent> timeline(String cycleId) {
        return events.timeline(cycleId);
    }

    public Page<EvolutionEvent> events(EventFilter filter, int page, int size) {
        return events.page(filter, page, size);
    }
}
