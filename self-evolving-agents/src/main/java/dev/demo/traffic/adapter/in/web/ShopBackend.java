package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.application.port.out.TrafficMetricsPort;
import dev.demo.traffic.domain.policy.TrafficPolicy;

/** The simulated protected backend: sleeps for the policy's work duration and records measured latency. */
public class ShopBackend {
    private final TrafficMetricsPort traffic;

    public ShopBackend(TrafficMetricsPort traffic) {
        this.traffic = traffic;
    }

    public void handle(TrafficPolicy policy) throws InterruptedException {
        traffic.backendStarted();
        long started = System.nanoTime();
        boolean ok = false;
        try {
            Thread.sleep(policy.simulatedWorkMs());
            ok = true;
        } finally {
            traffic.backendFinished((System.nanoTime() - started) / 1_000_000, ok);
        }
    }
}
