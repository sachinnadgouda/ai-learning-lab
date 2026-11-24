package dev.example.rag.infrastructure;

import java.time.Duration;

final class TestProperties {
    private TestProperties() {}

    static RagProperties defaults() {
        return new RagProperties("http://ollama", "http://qdrant", "test-collection", "questions.json",
                "chat-model", "embed-model", 5, 0.5, 0.5, 1100, 150, 10_000_000, 256, 0.1, 2, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
