package dev.example.rag.infrastructure;

import dev.example.rag.application.RagService;
import dev.example.rag.application.RetrievalSettings;
import dev.example.rag.application.TextChunker;
import dev.example.rag.application.port.AnswerPort;
import dev.example.rag.application.port.DocumentReader;
import dev.example.rag.application.port.EmbeddingPort;
import dev.example.rag.application.port.GeneralAnswerPort;
import dev.example.rag.application.port.VectorStorePort;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient ollamaClient(RagProperties properties) {
        return client(properties.ollamaUrl(), properties.connectTimeout(), properties.ollamaReadTimeout());
    }

    @Bean
    RestClient qdrantClient(RagProperties properties) {
        return client(properties.qdrantUrl(), properties.connectTimeout(), properties.qdrantReadTimeout());
    }

    @Bean
    TextChunker textChunker(RagProperties properties) {
        return new TextChunker(properties.chunkSize(), properties.chunkOverlap());
    }

    @Bean
    RagService ragService(DocumentReader reader, EmbeddingPort embeddings, VectorStorePort vectors,
                          AnswerPort answers, GeneralAnswerPort generalAnswers, TextChunker chunker,
                          RagProperties properties) {
        var settings = new RetrievalSettings(properties.topK(), properties.minRelevanceScore(),
                properties.minQuestionCoverage());
        return new RagService(reader, embeddings, vectors, answers, generalAnswers, chunker, settings);
    }

    private static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
