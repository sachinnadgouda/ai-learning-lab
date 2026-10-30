package dev.example.rag.application;
import dev.example.rag.application.port.*;
import dev.example.rag.domain.Match;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RagServiceTest {
  private final DocumentReader reader=mock(DocumentReader.class);
  private final EmbeddingPort embeddings=mock(EmbeddingPort.class);
  private final VectorStorePort vectors=mock(VectorStorePort.class);
  private final AnswerPort answers=mock(AnswerPort.class);
  private final GeneralAnswerPort generalAnswers=mock(GeneralAnswerPort.class);
  private final RagService service=new RagService(reader,embeddings,vectors,answers,generalAnswers,new TextChunker(20,3),5,0.5,0.6);
  @Test void ingestIndexesDocument() {
    when(reader.readPdf("https://example.org/a.pdf")).thenReturn("A recipe for green curry");
    when(embeddings.embed(anyString())).thenReturn(List.of(0.1,0.2));
    var result=service.ingest("https://example.org/a.pdf");
    assertThat(result.chunks()).isPositive();
    verify(vectors).upsert(anyList(),anyList());
  }
  @Test void questionWithNoMatchesFallsBackToGeneralAnswerWithAdvisory() {
    when(embeddings.embed("question")).thenReturn(List.of(0.1));
    when(vectors.search(List.of(0.1),5)).thenReturn(List.of());
    when(generalAnswers.answerWithoutRag("question")).thenReturn("General guidance");
    var result=service.ask("question");
    assertThat(result.answer()).isEqualTo("General guidance");
    assertThat(result.grounded()).isFalse();
    assertThat(result.source()).isEqualTo("internet");
    assertThat(result.advisory()).contains("manager or HR");
    verifyNoInteractions(answers);
  }
  @Test void questionWithMatchesPassesContextToLlm() {
    when(embeddings.embed("coconut?")).thenReturn(List.of(0.1));
    when(vectors.search(List.of(0.1),5)).thenReturn(List.of(new Match("doc","Curry has coconut milk",0.9)));
    when(answers.answer(eq("coconut?"),contains("Curry has coconut milk"))).thenReturn("Green curry");
    var result=service.ask("coconut?");
    assertThat(result.answer()).isEqualTo("Green curry");
    assertThat(result.sources()).hasSize(1);
    assertThat(result.grounded()).isTrue();
    assertThat(result.source()).isEqualTo("rag");
    assertThat(result.advisory()).isNull();
  }
  @Test void questionWithoutCoverageFallsBackToGeneralAnswer() {
    when(embeddings.embed("Home office instrument subsidy policy?")).thenReturn(List.of(0.2));
    when(vectors.search(List.of(0.2),5)).thenReturn(List.of(new Match("doc","Employees may work from home two days per week.",0.84)));
    when(generalAnswers.answerWithoutRag("Home office instrument subsidy policy?")).thenReturn("General subsidy information");
    var result=service.ask("Home office instrument subsidy policy?");
    assertThat(result.answer()).isEqualTo("General subsidy information");
    assertThat(result.grounded()).isFalse();
    assertThat(result.source()).isEqualTo("internet");
    verifyNoInteractions(answers);
  }
  @Test void askWithoutRagUsesGeneralAnswerPort() {
    when(generalAnswers.answerWithoutRag("What is annual leave?")).thenReturn("General annual leave guidance");
    var result=service.askWithoutRag("What is annual leave?");
    assertThat(result.answer()).isEqualTo("General annual leave guidance");
    assertThat(result.sources()).isEmpty();
    assertThat(result.grounded()).isFalse();
    assertThat(result.source()).isEqualTo("internet");
    assertThat(result.advisory()).contains("without RAG context");
  }
}
