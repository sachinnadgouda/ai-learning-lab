package dev.demo.traffic.domain.policy;

import java.util.Objects;

/** Immutable traffic policy. Invariants are enforced on construction. */
public record TrafficPolicy(int version, PolicyMode mode, int admissionPerSecond, int maxQueue, int simulatedWorkMs) {
    public TrafficPolicy {
        Objects.requireNonNull(mode, "mode");
        if (version < 1 || admissionPerSecond < 1 || admissionPerSecond > PolicyBounds.MAX_ADMISSION
                || maxQueue < 0 || maxQueue > PolicyBounds.MAX_QUEUE
                || simulatedWorkMs < 0 || simulatedWorkMs > PolicyBounds.MAX_WORK_MS) {
            throw new IllegalArgumentException("Policy outside demo safety bounds");
        }
    }

    public static TrafficPolicy defaultPolicy() {
        return new TrafficPolicy(1, PolicyMode.STATIC, 60, 500, 30);
    }

    /** True when both policies behave identically; the version number is ignored (semantic no-op detection). */
    public boolean sameBehavior(TrafficPolicy other) {
        return mode == other.mode && admissionPerSecond == other.admissionPerSecond
                && maxQueue == other.maxQueue && simulatedWorkMs == other.simulatedWorkMs;
    }

    public boolean usesWaitingRoom() {
        return mode != PolicyMode.STATIC;
    }

    /** Stable identity of the operational parameters; used to bind evaluation and approval to one candidate. */
    public String fingerprint() {
        return version + ":" + mode + ":" + admissionPerSecond + ":" + maxQueue + ":" + simulatedWorkMs;
    }
}
