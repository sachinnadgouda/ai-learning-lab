package dev.example.rag.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Question(UUID id, String text, Instant askedAt, PolicyStatus policyStatus,
                       String aiAnswer, List<Match> sources, String hrReply, Instant answeredAt) {
    public Question {
        sources = List.copyOf(sources);
    }

    public boolean needsReview() {
        return answeredAt == null && (policyStatus == PolicyStatus.NO_MATCH || policyStatus == PolicyStatus.MATCH_FOUND);
    }

    public Question withMatches(List<Match> matches) {
        return new Question(id, text, askedAt, matches.isEmpty() ? PolicyStatus.NO_MATCH : PolicyStatus.MATCH_FOUND,
                aiAnswer, matches, hrReply, answeredAt);
    }

    public Question withReply(String reply) {
        return new Question(id, text, askedAt, policyStatus, aiAnswer, sources, reply, Instant.now());
    }

    public enum PolicyStatus { MATCHED, NO_MATCH, MATCH_FOUND, NOT_CHECKED }
}
