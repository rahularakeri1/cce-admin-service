package org.openphc.cce.admin.exception;

import org.openphc.cce.admin.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every domain exception 1:1 to the error codes table in docs/api-reference.md. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
    return ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getCode(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .orElse("Validation failed");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("VALIDATION_ERROR", message));
  }

  /** Malformed JSON body — a client error, not a 500. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("VALIDATION_ERROR", "Malformed or unreadable request body"));
  }

  /** e.g. /roles/not-a-uuid — a client error, not a 500. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse("VALIDATION_ERROR", "Invalid value for '" + ex.getName() + "'"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
  }

  /** A method the route doesn't have (e.g. POST /menus since the catalog became edit-only): 405, not the 500 below. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(new ErrorResponse("METHOD_NOT_ALLOWED", "Method " + ex.getMethod() + " is not supported here"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("INTERNAL_ERROR", "Unexpected error"));
  }
}
