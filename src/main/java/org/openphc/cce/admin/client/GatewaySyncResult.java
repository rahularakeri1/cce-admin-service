package org.openphc.cce.admin.client;

import java.util.List;

/** What gateway-service's sync-to-keycloak reported. {@code failedRoles} is empty on a full success. */
public record GatewaySyncResult(
    int successCount, int failureCount, int deletedCount, List<String> failedRoles, String message) {

  public boolean fullySuccessful() {
    return failureCount == 0 && failedRoles.isEmpty();
  }
}
