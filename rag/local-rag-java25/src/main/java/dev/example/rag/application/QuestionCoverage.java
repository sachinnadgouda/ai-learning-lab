package dev.example.rag.application;

import dev.example.rag.domain.Match;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Keyword-coverage heuristic: how many significant question words appear in the retrieved passages. */
final class QuestionCoverage {
    private static final int MIN_TOKEN_LENGTH = 4;

    private final double minCoverage;

    QuestionCoverage(double minCoverage) {
        this.minCoverage = minCoverage;
    }

    boolean isCovered(String question, List<Match> matches) {
        List<String> tokens = tokens(question);
        if (tokens.isEmpty()) {
            return true;
        }
        String sourceText = String.join(" ", matches.stream().map(Match::text).toList()).toLowerCase(Locale.ROOT);
        long covered = tokens.stream().filter(sourceText::contains).count();
        return (double) covered / tokens.size() >= minCoverage;
    }

    static List<String> tokens(String question) {
        return Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(part -> part.length() >= MIN_TOKEN_LENGTH)
                .map(part -> part.endsWith("s") && part.length() > MIN_TOKEN_LENGTH
                        ? part.substring(0, part.length() - 1) : part)
                .toList();
    }
}
