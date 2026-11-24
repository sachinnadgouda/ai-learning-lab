package dev.example.rag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.example.rag.application.port.AnswerPort;
import dev.example.rag.application.port.DocumentReader;
import dev.example.rag.application.port.EmbeddingPort;
import dev.example.rag.application.port.GeneralAnswerPort;
import dev.example.rag.application.port.VectorStorePort;
import dev.example.rag.domain.Match;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagServiceTest {
    private final DocumentReader reader = mock(DocumentReader.class);
    private final EmbeddingPort embeddings = mock(EmbeddingPort.class);
    private final VectorStorePort vectors = mock(VectorStorePort.class);
    private final AnswerPort answers = mock(AnswerPort.class);
    private final GeneralAnswerPort generalAnswers = mock(GeneralAnswerPort.class);
    private final RagService service = new RagService(reader, embeddings, vectors, answers, generalAnswers,
            new TextChunker(20, 3), new RetrievalSettings(5, 0.5, 0.6));

    @Test
    void ingestIndexesDocumentInOneEmbeddingBatch() {
        when(reader.readPdfBytes(any(byte[].class))).thenReturn("Employees receive 25 days of annual leave.");
        when(embeddings.embedAll(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(text -> List.of(0.1, 0.2)).toList());

        var result = service.ingestUploadedPdf("dir\\leave.pdf", new byte[] {1});

        assertThat(result.chunks()).isPositive();
        verify(vectors).upsert(anyList(), anyList());
    }

    @Test
    void questionWithNoMatchesDoesNotPresentGeneralAdviceAsPolicy() {
        when(embeddings.embed("question")).thenReturn(List.of(0.1));
        when(vectors.search(List.of(0.1), 5)).thenReturn(List.of());

        var result = service.ask("question");

        assertThat(result.answer()).isNull();
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(answers, generalAnswers);
    }

    @Test
    void questionWithMatchesPassesContextToLlm() {
        when(embeddings.embed("Annual leave?")).thenReturn(List.of(0.1));
        when(vectors.search(List.of(0.1), 5))
                .thenReturn(List.of(new Match("doc", "leave.pdf", "Annual leave is 25 days.", 0.9)));
        when(answers.answer(eq("Annual leave?"), contains("Annual leave is 25 days."))).thenReturn("25 days");

        var result = service.ask("Annual leave?");

        assertThat(result.answer()).isEqualTo("25 days");
        assertThat(result.sources()).hasSize(1);
    }

    @Test
    void lowScoringMatchesAreDiscarded() {
        when(embeddings.embed("Annual leave?")).thenReturn(List.of(0.1));
        when(vectors.search(List.of(0.1), 5))
                .thenReturn(List.of(new Match("doc", "leave.pdf", "Annual leave is 25 days.", 0.2)));

        assertThat(service.findPolicyMatches("Annual leave?")).isEmpty();
    }

    @Test
    void questionWithoutCoverageReturnsNoMatch() {
        when(embeddings.embed("Home office instrument subsidy policy?")).thenReturn(List.of(0.2));
        when(vectors.search(List.of(0.2), 5)).thenReturn(List.of(
                new Match("doc", "remote.pdf", "Employees may work from home two days per week.", 0.84)));

        var result = service.ask("Home office instrument subsidy policy?");

        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(answers, generalAnswers);
    }

    @Test
    void askWithoutRagUsesGeneralAnswerPort() {
        when(generalAnswers.answerWithoutRag("What is annual leave?")).thenReturn("General annual leave guidance");

        var result = service.askWithoutRag("What is annual leave?");

        assertThat(result.answer()).isEqualTo("General annual leave guidance");
        assertThat(result.sources()).isEmpty();
    }
}
