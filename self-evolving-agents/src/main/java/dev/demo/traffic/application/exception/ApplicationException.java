package dev.demo.traffic.application.exception;

/** Base type for expected business failures; adapters map subclasses to protocol-level errors. */
public abstract class ApplicationException extends RuntimeException {
    protected ApplicationException(String message) { super(message); }
    protected ApplicationException(String message, Throwable cause) { super(message, cause); }
}
