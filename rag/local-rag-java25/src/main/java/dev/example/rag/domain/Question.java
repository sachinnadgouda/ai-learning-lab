package dev.example.rag.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Question(UUID id, String text, Instant askedAt, PolicyStatus policyStatus,
                       String aiAnswer, List<Match> sources, String hrReply, Instant answeredAt) {
    public Question {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public boolean needsReview() {
        return answeredAt == null
                && (policyStatus == PolicyStatus.NO_MATCH || policyStatus == PolicyStatus.MATCH_FOUND);
    }

    public Question withMatches(List<Match> matches) {
        return new Question(id, text, askedAt, matches.isEmpty() ? PolicyStatus.NO_MATCH : PolicyStatus.MATCH_FOUND,
                aiAnswer, matches, hrReply, answeredAt);
    }

    public Question withReply(String reply, Instant answeredAt) {
        return new Question(id, text, askedAt, policyStatus, aiAnswer, sources, reply, answeredAt);
    }

    /**
     * MATCHED: the answer was generated from policy passages at ask time.
     * MATCH_FOUND: passages were found after a later upload; HR still has to reply.
     */
    public enum PolicyStatus { MATCHED, NO_MATCH, MATCH_FOUND, NOT_CHECKED }
}
