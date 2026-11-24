package dev.example.rag.api;

import dev.example.rag.application.QuestionService;
import dev.example.rag.domain.Question;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class RagController {
    private static final String BROWSER_HEADER = "X-Demo-Browser-Id";
    private final QuestionService service;

    public RagController(QuestionService service) {
        this.service = service;
    }

    @PostMapping(value = "/documents/pdf", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionService.UploadResult ingestPdf(@RequestPart("file") MultipartFile file) throws IOException {
        return service.upload(file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public Question ask(@RequestHeader(BROWSER_HEADER) UUID browserId, @Valid @RequestBody AskRequest request) {
        return service.ask(browserId, request.question(), request.searchPolicy());
    }

    @GetMapping("/questions")
    public List<Question> history(@RequestHeader(BROWSER_HEADER) UUID browserId) {
        return service.history(browserId);
    }

    @GetMapping("/hr/questions")
    public List<Question> allQuestions() {
        return service.allQuestions();
    }

    @PutMapping("/hr/questions/{id}/reply")
    public ResponseEntity<Question> reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest request) {
        return ResponseEntity.of(service.reply(id, request.reply()));
    }

    public record AskRequest(@NotBlank @Size(max = 2000) String question, @NotNull Boolean searchPolicy) {}

    public record ReplyRequest(@NotBlank @Size(max = 4000) String reply) {}
}
