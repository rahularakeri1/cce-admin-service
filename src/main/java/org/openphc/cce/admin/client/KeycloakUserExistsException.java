package org.openphc.cce.admin.client;

/**
 * Keycloak definitively rejected the create because that username or email already exists there —
 * a clean "no" (nothing was created), unlike an ambiguous timeout.
 */
public class KeycloakUserExistsException extends RuntimeException {

  public enum Field {
    USERNAME,
    EMAIL
  }

  private final Field field;

  public KeycloakUserExistsException(Field field, String message) {
    super(message);
    this.field = field;
  }

  public Field getField() {
    return field;
  }
}
