package dev.demo.traffic.domain.evolution;

public enum EvolutionStage {
    OBSERVED, TRIGGERED, AI_CALLED, PROPOSED, EVALUATED, APPROVED, ACTIVATED,
    NO_CHANGE, INSUFFICIENT_DATA, REJECTED, FAILED, ROLLED_BACK,
    /** Non-lifecycle diagnostic events (flags, connectivity, admission summaries, ...). */
    DIAGNOSTIC;

    public boolean terminal() {
        return switch (this) {
            case ACTIVATED, NO_CHANGE, INSUFFICIENT_DATA, REJECTED, FAILED, ROLLED_BACK -> true;
            default -> false;
        };
    }
}
