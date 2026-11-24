package dev.example.rag.infrastructure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag")
public record RagProperties(
        @NotBlank String ollamaUrl,
        @NotBlank String qdrantUrl,
        @NotBlank String collection,
        @NotBlank String questionFile,
        @NotBlank String chatModel,
        @NotBlank String embeddingModel,
        @Positive int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double minRelevanceScore,
        @DecimalMin("0.0") @DecimalMax("1.0") double minQuestionCoverage,
        @Positive int chunkSize,
        @PositiveOrZero int chunkOverlap,
        @Positive int maxPdfBytes,
        @Positive int answerMaxTokens,
        @DecimalMin("0.0") double temperature,
        @Positive int embeddingBatchSize,
        @Positive int upsertBatchSize,
        @NotNull Duration connectTimeout,
        @NotNull Duration ollamaReadTimeout,
        @NotNull Duration qdrantReadTimeout) {}
