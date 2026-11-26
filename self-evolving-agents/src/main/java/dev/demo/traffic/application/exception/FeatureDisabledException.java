package dev.demo.traffic.application.exception;

public class FeatureDisabledException extends ApplicationException {
    public FeatureDisabledException(String message) { super(message); }
    public FeatureDisabledException(String message, Throwable cause) { super(message, cause); }
}
