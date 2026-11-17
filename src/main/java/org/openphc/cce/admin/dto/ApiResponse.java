package org.openphc.cce.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Success envelope: { "data": ..., "pagination"?: ..., "warnings"?: [...] } — see docs/api-reference.md. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

  private final T data;
  private PageMeta pagination;
  private List<String> warnings;

  private ApiResponse(T data) {
    this.data = data;
  }

  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data);
  }

  public static <T> ApiResponse<T> of(T data, PageMeta pagination) {
    ApiResponse<T> response = new ApiResponse<>(data);
    response.pagination = pagination;
    return response;
  }

  public ApiResponse<T> withWarnings(List<String> warnings) {
    this.warnings = warnings;
    return this;
  }

  public T getData() {
    return data;
  }

  public PageMeta getPagination() {
    return pagination;
  }

  public List<String> getWarnings() {
    return warnings;
  }
}
