package org.openphc.cce.admin.client;

import java.util.Collection;
import java.util.List;

/**
 * Everything this service needs from Keycloak's Admin API. An interface so the service layer can be
 * tested with a fake and no running Keycloak. Every method throws
 * {@link org.openphc.cce.admin.exception.SyncFailedException} when Keycloak is unreachable, times
 * out, or answers unexpectedly — a timeout is AMBIGUOUS (Keycloak may have acted anyway).
 */
public interface KeycloakGateway {

  /** @return the given names that do NOT exist as realm roles (empty when all do) */
  List<String> missingRealmRoles(Collection<String> roleNames);

  /**
   * @return the new Keycloak user id
   * @throws KeycloakUserExistsException on a definitive duplicate username/email
   */
  String createUser(KeycloakUserSpec spec);

  void assignRealmRoles(String keycloakUserId, Collection<String> roleNames);

  /**
   * Looks a user up by USERNAME — the one identifier that never changes (no keycloak_id is stored
   * locally, and email is editable). Throws SyncFailedException if Keycloak has no such user.
   */
  String findUserId(String username);

  /**
   * Overwrites the profile fields (and the email-verified flag, unless null).
   *
   * @return the profile as it was BEFORE the change, so the caller can restore it exactly
   */
  KeycloakUserProfile updateUser(String keycloakUserId, KeycloakUserProfile profile);

  /** Enables or disables the account (a disabled account cannot log in or refresh). */
  void setEnabled(String keycloakUserId, boolean enabled);

  /** Removes exactly these realm roles (a name the user doesn't hold is ignored). */
  void removeRealmRoles(String keycloakUserId, Collection<String> roleNames);

  /** Ends every session of the user, so already-issued refresh tokens stop working at once. */
  void logoutUser(String keycloakUserId);

  /** Sends ONE email carrying all the given required actions, valid for {@code lifespanSeconds}. */
  void sendActionsEmail(String keycloakUserId, List<String> actions, long lifespanSeconds);
}
