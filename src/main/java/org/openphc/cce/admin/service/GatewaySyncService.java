package org.openphc.cce.admin.service;

import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.GatewaySyncResult;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT @Transactional: it must only ever run after the caller's database change has
 * committed, so a sync failure can never roll that change back (docs/flow-diagrams.md Flow 4).
 */
@Service
public class GatewaySyncService {

  private static final Logger log = LoggerFactory.getLogger(GatewaySyncService.class);

  private final GatewaySyncClient client;

  public GatewaySyncService(GatewaySyncClient client) {
    this.client = client;
  }

  /**
   * Runs the (global) sync and judges it for one role: a partial 206 only counts as a failure if
   * THIS role is among the failed ones — another role's failure must not block this one.
   *
   * @throws SyncFailedException when gateway is unreachable/erroring or this role failed
   */
  public void syncFor(String roleName) {
    GatewaySyncResult result = client.sync();
    if (result.failedRoles().contains(roleName)) {
      throw new SyncFailedException("Keycloak sync failed for role " + roleName + ": " + result.message());
    }
    if (!result.fullySuccessful()) {
      log.warn("Gateway sync partially failed for other roles {}: {}", result.failedRoles(), result.message());
    }
  }

  /** For follow-up cleanups where the primary change already succeeded: failure is logged only. */
  public boolean trySync() {
    try {
      client.sync();
      return true;
    } catch (SyncFailedException e) {
      log.warn("Best-effort gateway sync failed; the next sync will reconcile: {}", e.getMessage());
      return false;
    }
  }
}
