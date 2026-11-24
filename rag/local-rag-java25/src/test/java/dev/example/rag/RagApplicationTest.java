package dev.example.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.example.rag.application.QuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "rag.question-file=target/test-data/questions.json")
class RagApplicationTest {
    @Autowired QuestionService service;

    @Test
    void contextLoads() {
        assertThat(service).isNotNull();
    }
}
