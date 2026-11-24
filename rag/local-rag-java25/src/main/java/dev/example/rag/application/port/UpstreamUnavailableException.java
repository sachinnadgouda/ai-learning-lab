package dev.example.rag.application.port;

/** A required backing service (Ollama, Qdrant) failed or was unreachable. */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
