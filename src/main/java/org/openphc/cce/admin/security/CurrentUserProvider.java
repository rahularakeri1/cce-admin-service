package org.openphc.cce.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Reads the CurrentUser the filter attached to this request — see CurrentUserAuthenticationFilter. */
@Component
public class CurrentUserProvider {

  public static final String REQUEST_ATTRIBUTE = "cce.currentUser";

  private final HttpServletRequest request;

  public CurrentUserProvider(HttpServletRequest request) {
    this.request = request;
  }

  public CurrentUser get() {
    Object value = request.getAttribute(REQUEST_ATTRIBUTE);
    if (value == null) {
      throw new IllegalStateException(
          "No CurrentUser on this request — CurrentUserAuthenticationFilter did not run, or this"
              + " endpoint is unauthenticated");
    }
    return (CurrentUser) value;
  }
}
