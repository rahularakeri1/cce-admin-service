package org.openphc.cce.admin.exception;

import org.springframework.http.HttpStatus;

/** Keycloak or gateway sync call failed; local state may be partially applied — see docs/architecture-overview.md §7. */
public class SyncFailedException extends ApiException {

  public SyncFailedException(String message) {
    super(HttpStatus.BAD_GATEWAY, "KEYCLOAK_SYNC_FAILED", message);
  }

  public SyncFailedException(String message, Throwable cause) {
    super(HttpStatus.BAD_GATEWAY, "KEYCLOAK_SYNC_FAILED", message);
    initCause(cause);
  }
}
