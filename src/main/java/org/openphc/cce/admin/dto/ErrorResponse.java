package org.openphc.cce.admin.dto;

/** Error envelope: { "error": { "code": ..., "message": ... } } — see docs/api-reference.md. */
public class ErrorResponse {

  private final ErrorBody error;

  public ErrorResponse(String code, String message) {
    this.error = new ErrorBody(code, message);
  }

  public ErrorBody getError() {
    return error;
  }

  public static class ErrorBody {
    private final String code;
    private final String message;

    public ErrorBody(String code, String message) {
      this.code = code;
      this.message = message;
    }

    public String getCode() {
      return code;
    }

    public String getMessage() {
      return message;
    }
  }
}
