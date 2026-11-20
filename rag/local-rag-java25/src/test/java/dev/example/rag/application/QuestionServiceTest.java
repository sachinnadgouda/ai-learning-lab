package dev.example.rag.application;

import dev.example.rag.domain.Match;
import dev.example.rag.domain.Question.PolicyStatus;
import dev.example.rag.infrastructure.FileQuestionStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuestionServiceTest {
    @TempDir Path temp;
    private final RagService rag = mock(RagService.class);
    private final UUID browser = UUID.randomUUID();

    @Test
    void uploadSuggestsPolicyEvidenceWithoutResolvingAndReplySurvivesRestart() throws IOException {
        var service = service();
        byte[] pdf = {1};
        var passage = new Match("doc", "leave.pdf", "Annual leave is 25 days.", 0.9);
        when(rag.ask("Annual leave?")).thenReturn(new RagService.QueryResult(null, List.of()));
        when(rag.askWithoutRag("General guidance?")).thenReturn(new RagService.QueryResult("It varies.", List.of()));
        when(rag.ingestUploadedPdf("leave.pdf", pdf)).thenReturn(new RagService.IngestResult("doc", 1));
        when(rag.findPolicyMatches("Annual leave?")).thenReturn(List.of(passage));

        var miss = service.ask(browser, "Annual leave?", true);
        var unchecked = service.ask(UUID.randomUUID(), "General guidance?", false);
        assertThat(miss.policyStatus()).isEqualTo(PolicyStatus.NO_MATCH);
        assertThat(unchecked.policyStatus()).isEqualTo(PolicyStatus.NOT_CHECKED);
        assertThat(service.history(browser)).containsExactly(miss);

        assertThat(service.upload("leave.pdf", pdf).matchesFound()).isEqualTo(1);
        var suggested = service.history(browser).getFirst();
        assertThat(suggested.policyStatus()).isEqualTo(PolicyStatus.MATCH_FOUND);
        assertThat(suggested.sources()).containsExactly(passage);
        assertThat(suggested.needsReview()).isTrue();
        assertThat(suggested.answeredAt()).isNull();
        verify(rag, never()).findPolicyMatches("General guidance?");

        service.reply(miss.id(), "You receive 25 days.");
        var restarted = service();
        var resolved = restarted.history(browser).getFirst();
        assertThat(resolved.hrReply()).isEqualTo("You receive 25 days.");
        assertThat(resolved.answeredAt()).isNotNull();
        assertThat(resolved.needsReview()).isFalse();
        assertThat(resolved.sources()).containsExactly(passage);
        assertThat(restarted.history(UUID.randomUUID())).isEmpty();
        assertThat(restarted.allQuestions()).hasSize(2);
        assertThat(restarted.reply(UUID.randomUUID(), "Unknown")).isEmpty();
        assertThat(restarted.upload("leave.pdf", pdf).matchesFound()).isZero();
        verify(rag).findPolicyMatches("Annual leave?");
    }

    @Test
    void recheckFailureLeavesQuestionOpenAndReportsPartialSuccess() throws IOException {
        var service = service();
        byte[] pdf = {1};
        when(rag.ask("Annual leave?")).thenReturn(new RagService.QueryResult(null, List.of()));
        when(rag.ingestUploadedPdf("leave.pdf", pdf)).thenReturn(new RagService.IngestResult("doc", 1));
        when(rag.findPolicyMatches("Annual leave?")).thenThrow(new ResourceAccessException("unavailable"));

        var miss = service.ask(browser, "Annual leave?", true);
        assertThat(service.upload("leave.pdf", pdf).recheckFailures()).isEqualTo(1);
        assertThat(service.history(browser)).containsExactly(miss);
    }

    private QuestionService service() throws IOException {
        return new QuestionService(rag, new FileQuestionStore(temp.resolve("questions.json").toString(), new JsonMapper()));
    }
}
