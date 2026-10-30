package dev.example.rag.api;

import dev.example.rag.application.RagService;

import java.io.IOException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class RagController {
    private final RagService service;

    public RagController(RagService service) {this.service = service;}

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public RagService.IngestResult ingest(@Valid @RequestBody IngestRequest request) {
        return service.ingest(request.url());
    }

    @PostMapping(value = "/documents/pdf", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public RagService.IngestResult ingestPdf(@RequestPart("file") MultipartFile file) throws
        IOException {return service.ingestUploadedPdf(file.getOriginalFilename(), file.getBytes());}

    @PostMapping("/questions")
    public RagService.QueryResult ask(@Valid @RequestBody AskRequest request) {return service.ask(request.question());}

    @PostMapping("/questions/general")
    public RagService.QueryResult askWithoutRag(@Valid @RequestBody AskRequest request) {
        return service.askWithoutRag(request.question());
    }

    public record IngestRequest(@NotBlank String url) {
    }
    public record AskRequest(@NotBlank @Size(max = 2000) String question) {
    }
}
