package org.openphc.cce.admin.client;

/**
 * Wraps gateway-service's POST /api/permissions/sync-to-keycloak. An interface so tests (and local
 * development without a gateway) can substitute it. The endpoint is GLOBAL: it reloads every
 * api_permissions row into gateway and pushes every distinct permission_name to Keycloak, deleting
 * realm roles that have no rows. There is no per-role variant.
 */
public interface GatewaySyncClient {

  /**
   * @return gateway's result for a 200 or a partial 206 (which carries {@code failedRoles})
   * @throws org.openphc.cce.admin.exception.SyncFailedException if gateway is unreachable, times
   *     out, or answers with anything else
   */
  GatewaySyncResult sync();
}
