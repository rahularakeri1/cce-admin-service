package org.openphc.cce.admin.exception;

import org.springframework.http.HttpStatus;

/** Base for every domain exception GlobalExceptionHandler maps 1:1 to an error code. */
public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
