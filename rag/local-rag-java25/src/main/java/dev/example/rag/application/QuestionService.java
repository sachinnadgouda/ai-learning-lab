package dev.example.rag.application;

import dev.example.rag.application.port.QuestionStore;
import dev.example.rag.domain.Question;
import dev.example.rag.domain.Question.PolicyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class QuestionService {
    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);
    private final RagService rag;
    private final QuestionStore store;

    public QuestionService(RagService rag, QuestionStore store) {
        this.rag = rag;
        this.store = store;
    }

    // Keep submissions ordered with upload/recheck in this single-instance demo.
    public synchronized Question ask(UUID browserId, String text, boolean searchPolicy) {
        text = text.strip();
        var result = searchPolicy ? rag.ask(text) : rag.askWithoutRag(text);
        var status = !searchPolicy ? PolicyStatus.NOT_CHECKED
                : result.sources().isEmpty() ? PolicyStatus.NO_MATCH : PolicyStatus.MATCHED;
        var question = new Question(UUID.randomUUID(), text, Instant.now(), status,
                result.answer(), result.sources(), null, null);
        store.add(browserId, question);
        return question;
    }

    public synchronized UploadResult upload(String filename, byte[] pdf) {
        var document = rag.ingestUploadedPdf(filename, pdf);
        int matchesFound = 0;
        int recheckFailures = 0;
        for (Question question : store.list()) {
            if (!question.needsReview()) continue;
            try {
                var matches = rag.findPolicyMatches(question.text());
                var updated = store.update(question.id(), current -> current.needsReview()
                        ? current.withMatches(matches) : current).orElseThrow();
                if (updated.needsReview() && updated.policyStatus() == PolicyStatus.MATCH_FOUND) matchesFound++;
            } catch (RestClientException | IllegalStateException e) {
                log.warn("Policy recheck failed for question={} ({})", question.id(), e.getClass().getSimpleName());
                recheckFailures++;
            }
        }
        return new UploadResult(document.documentId(), document.chunks(), matchesFound, recheckFailures);
    }

    public List<Question> history(UUID browserId) {
        return store.listFor(browserId);
    }

    public List<Question> allQuestions() {
        return store.list();
    }

    public Optional<Question> reply(UUID id, String reply) {
        return store.update(id, question -> question.withReply(reply.strip()));
    }

    public record UploadResult(String documentId, int chunks, int matchesFound, int recheckFailures) {}
}
