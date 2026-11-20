package dev.example.rag.application.port;

import dev.example.rag.domain.Question;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

public interface QuestionStore {
    void add(UUID browserId, Question question);
    List<Question> list();
    List<Question> listFor(UUID browserId);
    Optional<Question> update(UUID id, UnaryOperator<Question> change);
}
