package dev.demo.traffic.application.port.out;

import dev.demo.traffic.application.model.Page;
import dev.demo.traffic.domain.evolution.EvolutionExperiment;
import java.util.List;
import java.util.Optional;

public interface ExperimentRepository {
    /** Inserts or replaces by cycle id; newest first ordering. */
    void save(EvolutionExperiment experiment);

    List<EvolutionExperiment> recent(int limit);

    Optional<EvolutionExperiment> find(String cycleId);

    Page<EvolutionExperiment> page(int page, int size);
}
