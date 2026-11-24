package dev.example.rag.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.example.rag.domain.Question;
import dev.example.rag.domain.Question.PolicyStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileQuestionStoreTest {
    @TempDir Path temp;

    @Test
    void corruptHistoryFileFailsStartupWithClearMessage() throws IOException {
        Path file = temp.resolve("questions.json");
        Files.writeString(file, "{not json");

        assertThatThrownBy(() -> new FileQuestionStore(file.toString(), new JsonMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("questions.json");
    }

    @Test
    void persistsAndReloadsEntriesAndLeavesNoTempFiles() throws IOException {
        Path file = temp.resolve("nested/questions.json");
        UUID browser = UUID.randomUUID();
        var question = new Question(UUID.randomUUID(), "Leave?", Instant.parse("2026-01-01T00:00:00Z"),
                PolicyStatus.NO_MATCH, null, List.of(), null, null);
        new FileQuestionStore(file.toString(), new JsonMapper()).add(browser, question);

        var reloaded = new FileQuestionStore(file.toString(), new JsonMapper());

        assertThat(reloaded.listFor(browser)).containsExactly(question);
        assertThat(reloaded.listFor(UUID.randomUUID())).isEmpty();
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("questions.json");
        }
    }

    @Test
    void legacyEntriesWithoutSourcesAreLoaded() throws IOException {
        Path file = temp.resolve("questions.json");
        UUID id = UUID.randomUUID();
        Files.writeString(file, """
                [{"browserId":"%s","question":{"id":"%s","text":"Leave?","askedAt":"2026-01-01T00:00:00Z",
                "policyStatus":"NO_MATCH"}}]""".formatted(UUID.randomUUID(), id));

        var store = new FileQuestionStore(file.toString(), new JsonMapper());

        assertThat(store.list()).singleElement().satisfies(q -> assertThat(q.sources()).isEmpty());
    }
}
