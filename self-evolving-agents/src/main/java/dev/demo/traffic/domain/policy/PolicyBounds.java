package dev.demo.traffic.domain.policy;

/** Safety envelopes. Hard bounds apply to every policy; AI bounds are stricter. */
public final class PolicyBounds {
    public static final int MAX_ADMISSION = 5000;
    public static final int MAX_QUEUE = 100_000;
    public static final int MAX_WORK_MS = 1000;
    public static final int AI_MIN_ADMISSION = 1;
    public static final int AI_MAX_ADMISSION = 1000;
    public static final int AI_MIN_QUEUE = 1;
    public static final int AI_MAX_QUEUE = 10_000;

    private PolicyBounds() {}
}
