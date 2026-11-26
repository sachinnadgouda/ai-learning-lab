package dev.demo.traffic.application.port.out;

import java.util.Optional;

public interface FeatureFlagRepository {
    Optional<Boolean> aiEnabled();

    void setAiEnabled(boolean enabled);

    Optional<Boolean> automaticEvolution();

    void setAutomaticEvolution(boolean enabled);

    Optional<String> model();

    void setModel(String model);

    Optional<Integer> triggerArrivals();

    void setTriggerArrivals(int arrivals);
}
