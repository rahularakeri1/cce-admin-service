package org.openphc.cce.admin.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

  private NotFoundException(String code, String message) {
    super(HttpStatus.NOT_FOUND, code, message);
  }

  public static NotFoundException user(Object id) {
    return new NotFoundException("USER_NOT_FOUND", "No user with id " + id);
  }

  public static NotFoundException role(Object id) {
    return new NotFoundException("ROLE_NOT_FOUND", "No role with id " + id);
  }

  public static NotFoundException menu(Object id) {
    return new NotFoundException("MENU_NOT_FOUND", "No menu with id " + id);
  }
}
