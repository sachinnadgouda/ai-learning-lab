package dev.demo.traffic.application.exception;

public class AiUnavailableException extends ApplicationException {
    public AiUnavailableException(String message) { super(message); }
    public AiUnavailableException(String message, Throwable cause) { super(message, cause); }
}
