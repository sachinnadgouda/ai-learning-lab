package dev.example.rag.infrastructure;

import dev.example.rag.application.port.QuestionStore;
import dev.example.rag.domain.Question;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class FileQuestionStore implements QuestionStore {
    private final Path file;
    private final ObjectMapper mapper;
    private List<Entry> entries;

    public FileQuestionStore(@Value("${rag.question-file}") String file, ObjectMapper mapper) throws IOException {
        this.file = Path.of(file).toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(this.file.getParent());
        entries = Files.notExists(this.file) ? List.of()
                : Arrays.asList(mapper.readValue(this.file.toFile(), Entry[].class));
    }

    @Override
    public synchronized void add(UUID browserId, Question question) {
        var next = new ArrayList<>(entries);
        next.addFirst(new Entry(browserId, question));
        save(next);
    }

    @Override
    public synchronized List<Question> list() {
        return entries.stream().map(Entry::question).toList();
    }

    @Override
    public synchronized List<Question> listFor(UUID browserId) {
        return entries.stream().filter(entry -> entry.browserId().equals(browserId)).map(Entry::question).toList();
    }

    @Override
    public synchronized Optional<Question> update(UUID id, UnaryOperator<Question> change) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.question().id().equals(id)) continue;
            Question updated = change.apply(entry.question());
            if (!updated.equals(entry.question())) {
                var next = new ArrayList<>(entries);
                next.set(i, new Entry(entry.browserId(), updated));
                save(next);
            }
            return Optional.of(updated);
        }
        return Optional.empty();
    }

    private void save(List<Entry> next) {
        try {
            Path temp = Files.createTempFile(file.getParent(), "questions-", ".tmp");
            try {
                mapper.writeValue(temp.toFile(), next);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                entries = List.copyOf(next);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save question history", e);
        }
    }

    private record Entry(UUID browserId, Question question) {}
}
