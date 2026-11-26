package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.application.exception.AiUnavailableException;
import dev.demo.traffic.application.exception.ConflictException;
import dev.demo.traffic.application.exception.FeatureDisabledException;
import dev.demo.traffic.application.exception.InvalidRequestException;
import dev.demo.traffic.application.exception.NotFoundException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Stable RFC 9457 error contract. Internals (stack traces, SQL, hosts) are never returned. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:problem:" + code));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        String requestId = MDC.get(RequestCorrelationFilter.REQUEST_ID);
        if (requestId != null) pd.setProperty("requestId", requestId);
        return pd;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) { return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage()); }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) { return problem(HttpStatus.CONFLICT, "conflict", e.getMessage()); }

    @ExceptionHandler(FeatureDisabledException.class)
    ProblemDetail disabled(FeatureDisabledException e) { return problem(HttpStatus.CONFLICT, "feature-disabled", e.getMessage()); }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class})
    ProblemDetail invalid(RuntimeException e) { return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage()); }

    @ExceptionHandler(AiUnavailableException.class)
    ProblemDetail aiUnavailable(AiUnavailableException e) {
        LOG.warn("AI model unavailable: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "ai-unavailable", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataAccess(DataAccessException e) {
        LOG.error("Data store failure: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "store-unavailable", "A backing store is temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        LOG.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> r.getMethodParameter().getParameterName() + ": "
                        + e.getDefaultMessage())).toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            pd.setProperty("code", "http-" + statusCode.value());
            String requestId = MDC.get(RequestCorrelationFilter.REQUEST_ID);
            if (requestId != null) pd.setProperty("requestId", requestId);
            if (statusCode.is4xxClientError()) pd.setDetail(HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
