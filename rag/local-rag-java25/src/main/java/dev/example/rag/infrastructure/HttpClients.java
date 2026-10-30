package dev.example.rag.infrastructure;
import dev.example.rag.application.*;
import dev.example.rag.application.port.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
@Configuration
public class HttpClients {
  @Bean RestClient ollamaClient(@Value("${rag.ollama-url}") String url) { return RestClient.builder().baseUrl(url).build(); }
  @Bean RestClient qdrantClient(@Value("${rag.qdrant-url}") String url) { return RestClient.builder().baseUrl(url).build(); }
  @Bean TextChunker textChunker(@Value("${rag.chunk-size}") int size,@Value("${rag.chunk-overlap}") int overlap) { return new TextChunker(size,overlap); }
  @Bean RagService ragService(DocumentReader reader,EmbeddingPort embedding,VectorStorePort vectors,AnswerPort answers,GeneralAnswerPort generalAnswers,TextChunker chunker,@Value("${rag.top-k}") int topK,@Value("${rag.min-relevance-score:0.5}") double minRelevanceScore,@Value("${rag.min-question-coverage:0.5}") double minQuestionCoverage) { return new RagService(reader,embedding,vectors,answers,generalAnswers,chunker,topK,minRelevanceScore,minQuestionCoverage); }
}
