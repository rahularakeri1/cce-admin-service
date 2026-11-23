package org.openphc.cce.admin.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

  public ConflictException(String code, String message) {
    super(HttpStatus.CONFLICT, code, message);
  }

  public static ConflictException duplicateRoleName(String name) {
    return new ConflictException("DUPLICATE_ROLE_NAME", "Role name already in use: " + name);
  }

  public static ConflictException duplicateMenuKey(String key) {
    return new ConflictException("DUPLICATE_MENU_KEY", "Menu key already in use: " + key);
  }

  public static ConflictException emailInUse(String email) {
    return new ConflictException("EMAIL_IN_USE", "Email already in use: " + email);
  }

  public static ConflictException roleInUse(String name) {
    return new ConflictException("ROLE_IN_USE", "Role is still assigned to users: " + name);
  }

  public static ConflictException systemRoleImmutable(String name) {
    return new ConflictException("SYSTEM_ROLE_IMMUTABLE", "System role cannot be modified or deleted: " + name);
  }
}
