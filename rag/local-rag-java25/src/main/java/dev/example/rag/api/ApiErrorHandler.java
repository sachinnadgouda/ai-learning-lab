package dev.example.rag.api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ProblemDetail badRequest(Exception e) {return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid request");}
  @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
  ProblemDetail unexpected(Exception e) {log.error("RAG dependency operation failed",e);return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,"RAG operation failed; check server logs");}
}
