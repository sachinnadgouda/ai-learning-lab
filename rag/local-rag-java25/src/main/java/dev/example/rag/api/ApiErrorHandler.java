package dev.example.rag.api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ProblemDetail invalidArgument(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage());
  }
  @ExceptionHandler({MethodArgumentNotValidException.class, ServletRequestBindingException.class,
      MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ProblemDetail badRequest(Exception e) {return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid request");}
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
  ProblemDetail oversizedPdf() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"PDF exceeds the upload size limit.");
  }
  @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
  ProblemDetail unexpected(Exception e) {log.error("RAG dependency operation failed",e);return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,"RAG operation failed; check server logs");}
}
