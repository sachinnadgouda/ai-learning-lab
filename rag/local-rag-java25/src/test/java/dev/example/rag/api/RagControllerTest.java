package dev.example.rag.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.example.rag.application.QuestionService;
import dev.example.rag.application.port.UpstreamUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RagControllerTest {
    private static final String BROWSER = "X-Demo-Browser-Id";
    private final QuestionService service = mock(QuestionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RagController(service))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void validationFailureIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/questions").header(BROWSER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\",\"searchPolicy\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBrowserHeaderIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/questions")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownPathAndWrongMethodKeepTheirStatusInsteadOfBecomingBadGateway() throws Exception {
        mvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/questions")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedMediaTypeKeepsItsStatus() throws Exception {
        mvc.perform(post("/api/v1/questions").header(BROWSER, UUID.randomUUID().toString())
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void upstreamFailureIsBadGatewayWithoutLeakingDetails() throws Exception {
        when(service.ask(any(), anyString(), anyBoolean()))
                .thenThrow(new UpstreamUnavailableException("secret internal host", null));

        mvc.perform(post("/api/v1/questions").header(BROWSER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Leave?\",\"searchPolicy\":true}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("RAG operation failed; check server logs"));
    }

    @Test
    void unexpectedFailureIsInternalServerError() throws Exception {
        when(service.allQuestions()).thenThrow(new IllegalStateException("boom"));

        mvc.perform(get("/api/v1/hr/questions")).andExpect(status().isInternalServerError());
    }

    @Test
    void invalidPdfIsBadRequestWithMessage() throws Exception {
        when(service.upload(anyString(), any())).thenThrow(new IllegalArgumentException("Upload a valid, unencrypted PDF."));

        mvc.perform(multipart("/api/v1/documents/pdf").file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Upload a valid, unencrypted PDF."));
    }

    @Test
    void replyToUnknownQuestionIsNotFound() throws Exception {
        when(service.reply(any(), anyString())).thenReturn(Optional.empty());

        mvc.perform(put("/api/v1/hr/questions/{id}/reply", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reply\":\"Yes\"}"))
                .andExpect(status().isNotFound());
    }
}
