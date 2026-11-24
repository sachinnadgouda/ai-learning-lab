package dev.example.rag.application;

import dev.example.rag.application.port.QuestionStore;
import dev.example.rag.application.port.UpstreamUnavailableException;
import dev.example.rag.domain.Question;
import dev.example.rag.domain.Question.PolicyStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QuestionService {
    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);
    private final RagService rag;
    private final QuestionStore store;
    private final Clock clock;
    // Bumped after every successful ingest so a question that raced with an upload can be rechecked.
    private final AtomicLong indexVersion = new AtomicLong();

    public QuestionService(RagService rag, QuestionStore store, Clock clock) {
        this.rag = rag;
        this.store = store;
        this.clock = clock;
    }

    public Question ask(UUID browserId, String text, boolean searchPolicy) {
        String question = text.strip();
        long versionBeforeSearch = indexVersion.get();
        var result = searchPolicy ? rag.ask(question) : rag.askWithoutRag(question);
        var status = !searchPolicy ? PolicyStatus.NOT_CHECKED
                : result.sources().isEmpty() ? PolicyStatus.NO_MATCH : PolicyStatus.MATCHED;
        var saved = new Question(UUID.randomUUID(), question, Instant.now(clock), status,
                result.answer(), result.sources(), null, null);
        store.add(browserId, saved);
        if (status == PolicyStatus.NO_MATCH && indexVersion.get() != versionBeforeSearch) {
            try {
                return recheck(saved).orElse(saved);
            } catch (UpstreamUnavailableException e) {
                log.warn("Policy recheck failed for question={} ({})", saved.id(), e.getClass().getSimpleName());
            }
        }
        return saved;
    }

    public UploadResult upload(String filename, byte[] pdf) {
        var document = rag.ingestUploadedPdf(filename, pdf);
        indexVersion.incrementAndGet();
        int matchesFound = 0;
        int recheckFailures = 0;
        for (Question question : store.list()) {
            if (!question.needsReview()) {
                continue;
            }
            try {
                var updated = recheck(question);
                if (updated.isPresent() && updated.get().needsReview()
                        && updated.get().policyStatus() == PolicyStatus.MATCH_FOUND) {
                    matchesFound++;
                }
            } catch (UpstreamUnavailableException e) {
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
        String text = reply.strip();
        Instant answeredAt = Instant.now(clock);
        return store.update(id, question -> question.withReply(text, answeredAt));
    }

    private Optional<Question> recheck(Question question) {
        var matches = rag.findPolicyMatches(question.text());
        return store.update(question.id(), current -> current.needsReview() ? current.withMatches(matches) : current);
    }

    public record UploadResult(String documentId, int chunks, int matchesFound, int recheckFailures) {}
}
