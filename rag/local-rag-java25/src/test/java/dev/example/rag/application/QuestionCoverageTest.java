package dev.example.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.example.rag.domain.Match;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionCoverageTest {
    private final QuestionCoverage coverage = new QuestionCoverage(0.5);

    @Test
    void ignoresShortWordsAndStripsTrailingPlural() {
        assertThat(QuestionCoverage.tokens("How many benefits for leave?")).containsExactly("many", "benefit", "leave");
    }

    @Test
    void coveredWhenEnoughQuestionWordsAppearInSources() {
        var sources = List.of(new Match("d", "n", "Employees receive 25 days of annual leave.", 0.9));

        assertThat(coverage.isCovered("How many days of leave?", sources)).isTrue();
        assertThat(coverage.isCovered("Home office instrument subsidy?", sources)).isFalse();
    }

    @Test
    void questionWithoutSignificantWordsIsCovered() {
        assertThat(coverage.isCovered("Is it ok?", List.of())).isTrue();
    }
}
