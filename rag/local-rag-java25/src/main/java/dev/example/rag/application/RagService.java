package dev.example.rag.application;

import dev.example.rag.application.port.AnswerPort;
import dev.example.rag.application.port.DocumentReader;
import dev.example.rag.application.port.EmbeddingPort;
import dev.example.rag.application.port.GeneralAnswerPort;
import dev.example.rag.application.port.VectorStorePort;
import dev.example.rag.domain.Chunk;
import dev.example.rag.domain.Match;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String DEFAULT_DOCUMENT_NAME = "Policy PDF";

    private final DocumentReader reader;
    private final EmbeddingPort embeddings;
    private final VectorStorePort vectors;
    private final AnswerPort answers;
    private final GeneralAnswerPort generalAnswers;
    private final TextChunker chunker;
    private final RetrievalSettings settings;
    private final QuestionCoverage coverage;

    public RagService(DocumentReader reader, EmbeddingPort embeddings, VectorStorePort vectors, AnswerPort answers,
                      GeneralAnswerPort generalAnswers, TextChunker chunker, RetrievalSettings settings) {
        this.reader = reader;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.answers = answers;
        this.generalAnswers = generalAnswers;
        this.chunker = chunker;
        this.settings = settings;
        this.coverage = new QuestionCoverage(settings.minQuestionCoverage());
    }

    public IngestResult ingestUploadedPdf(String filename, byte[] pdfBytes) {
        String name = filename == null ? DEFAULT_DOCUMENT_NAME : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank()) {
            name = DEFAULT_DOCUMENT_NAME;
        }
        return ingestDocument(UUID.nameUUIDFromBytes(pdfBytes).toString(), name, reader.readPdfBytes(pdfBytes));
    }

    public QueryResult askWithoutRag(String question) {
        log.info("General query started");
        String answer = generalAnswers.answerWithoutRag(question);
        log.info("General query completed");
        return new QueryResult(answer, List.of());
    }

    public QueryResult ask(String question) {
        log.info("RAG query started");
        List<Match> relevant = findPolicyMatches(question);
        if (relevant.isEmpty()) {
            log.info("RAG query completed without a policy match");
            return new QueryResult(null, List.of());
        }
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < relevant.size(); i++) {
            context.append("[Source ").append(i + 1).append("] ").append(relevant.get(i).text()).append("\n\n");
        }
        String answer = answers.answer(question, context.toString());
        log.info("RAG query completed matches={} grounded=true", relevant.size());
        return new QueryResult(answer, relevant);
    }

    public List<Match> findPolicyMatches(String question) {
        List<Match> relevant = vectors.search(embeddings.embed(question), settings.topK()).stream()
                .filter(match -> match.score() >= settings.minRelevanceScore())
                .toList();
        return coverage.isCovered(question, relevant) ? relevant : List.of();
    }

    private IngestResult ingestDocument(String documentId, String documentName, String text) {
        log.info("Ingestion started documentId={}", documentId);
        List<Chunk> chunks = chunker.split(documentId, documentName, text);
        List<List<Double>> embedded = embeddings.embedAll(chunks.stream().map(Chunk::text).toList());
        vectors.upsert(chunks, embedded);
        log.info("Ingestion completed documentId={} chunks={}", documentId, chunks.size());
        return new IngestResult(documentId, chunks.size());
    }

    public record IngestResult(String documentId, int chunks) {}

    public record QueryResult(String answer, List<Match> sources) {}
}
