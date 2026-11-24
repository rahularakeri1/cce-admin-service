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

  /** Sends ONE email carrying all the given required actions, valid for {@code lifespanSeconds}. */
  void sendActionsEmail(String keycloakUserId, List<String> actions, long lifespanSeconds);
}
