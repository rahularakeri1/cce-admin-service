package org.openphc.cce.admin.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

  public ConflictException(String code, String message) {
    super(HttpStatus.CONFLICT, code, message);
  }

  public static ConflictException duplicateRoleName(String name) {
    return new ConflictException("DUPLICATE_ROLE_NAME", "Role name already in use: " + name);
  }

  public static ConflictException emailInUse(String email) {
    return new ConflictException("EMAIL_IN_USE", "Email already in use: " + email);
  }

  public static ConflictException roleInUse(String name) {
    return new ConflictException("ROLE_IN_USE", "Role is still assigned to users: " + name);
  }

  public static ConflictException usernameInUse(String username) {
    return new ConflictException("USERNAME_IN_USE", "Username already in use: " + username);
  }

  public static ConflictException roleNotSynced(java.util.List<String> roleNames) {
    return new ConflictException(
        "ROLE_NOT_SYNCED",
        "Role(s) not in Keycloak yet: "
            + String.join(", ", roleNames)
            + " — open the role's Permissions tab and Save or Re-sync first");
  }

  public static ConflictException systemUserImmutable(String username) {
    return new ConflictException(
        "SYSTEM_USER_IMMUTABLE",
        "System account " + username + " cannot be edited, deactivated or have its roles changed from the console");
  }

  public static ConflictException cannotDeactivateSelf() {
    return new ConflictException("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account");
  }

  public static ConflictException lastSuperAdmin() {
    return new ConflictException(
        "LAST_SUPER_ADMIN",
        "This is the last active SUPER_ADMIN; doing this would leave nobody able to administer the console");
  }

  public static ConflictException userNotActive(String username) {
    return new ConflictException("USER_NOT_ACTIVE", "User " + username + " is not active; reactivate them first");
  }

  public static ConflictException roleNameReserved(String name) {
    return new ConflictException(
        "ROLE_NAME_RESERVED",
        "Name belongs to an existing gateway permission set and cannot be used for a new role: " + name);
  }

  public static ConflictException roleNeedsPermissions(String name) {
    return new ConflictException(
        "ROLE_IN_USE",
        "Users still hold role "
            + name
            + "; removing all its permissions would delete it from Keycloak. Reassign them first.");
  }

  public static ConflictException adminMenuRequired(java.util.List<String> labels) {
    return new ConflictException(
        "ADMIN_MENU_REQUIRED",
        "SUPER_ADMIN must keep every admin console tab; missing: " + String.join(", ", labels));
  }

  public static ConflictException superAdminMenuRequired(java.util.List<String> labels) {
    return new ConflictException(
        "SUPER_ADMIN_MENU_REQUIRED",
        "SUPER_ADMIN must keep every menu; missing: " + String.join(", ", labels));
  }

  public static ConflictException adminMenuSuperAdminOnly(java.util.List<String> labels, String roleName) {
    return new ConflictException(
        "ADMIN_MENU_SUPER_ADMIN_ONLY",
        String.join(", ", labels) + " can only be assigned to SUPER_ADMIN, not to " + roleName);
  }

  public static ConflictException menuKeyImmutable(String key) {
    return new ConflictException(
        "MENU_KEY_IMMUTABLE",
        "A menu's key can't be changed (roles and code refer to it by key); it is " + key);
  }

  public static ConflictException menuOrderOutdated(String console) {
    return new ConflictException(
        "MENU_ORDER_OUTDATED",
        "The " + console + " menu list changed since you loaded it (an entry was added or deleted)."
            + " Reload it and arrange it again.");
  }

  public static ConflictException adminMenuProtected(String label, String what) {
    return new ConflictException(
        "ADMIN_MENU_PROTECTED",
        "The admin console's own tab \"" + label + "\" is protected: " + what
            + " — the console builds its sidebar from it, and it would lock everyone out");
  }

  public static ConflictException systemRoleImmutable(String name) {
    return new ConflictException("SYSTEM_ROLE_IMMUTABLE", "System role cannot be modified or deleted: " + name);
  }
}
