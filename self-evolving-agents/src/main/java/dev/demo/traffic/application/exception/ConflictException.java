package dev.demo.traffic.application.exception;

public class ConflictException extends ApplicationException {
    public ConflictException(String message) { super(message); }
    public ConflictException(String message, Throwable cause) { super(message, cause); }
}
