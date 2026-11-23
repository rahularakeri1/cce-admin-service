package org.openphc.cce.admin.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.openphc.cce.admin.entity.AdminAuditLog;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY class that inserts into admin_audit_log — no service class should call
 * AdminAuditLogRepository directly (see docs/architecture-overview.md §6).
 */
@Component
public class AuditLogWriter {

  private final AdminAuditLogRepository repository;
  private final ObjectMapper objectMapper;

  public AuditLogWriter(AdminAuditLogRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /** Writes as part of whatever transaction is already open (the common, default case). */
  public void write(
      UUID actorId,
      AuditAction action,
      String entityType,
      String entityId,
      Object before,
      Object after,
      String ipAddress) {
    AdminAuditLog row = new AdminAuditLog();
    row.setId(UUID.randomUUID());
    row.setActorId(actorId);
    row.setAction(action.name());
    row.setEntityType(entityType);
    row.setEntityId(entityId);
    row.setBefore(toJson(before));
    row.setAfter(toJson(after));
    row.setIpAddress(ipAddress);
    repository.save(row);
  }

  /**
   * Writes in its OWN, independent transaction — required whenever the caller's own transaction
   * is about to be rolled back but the write must survive it (e.g. USER_PROVISIONING_FAILED, see
   * docs/flow-diagrams.md Flow 2). REQUIRES_NEW suspends any existing transaction for this call.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writeInNewTransaction(
      UUID actorId,
      AuditAction action,
      String entityType,
      String entityId,
      Object before,
      Object after,
      String ipAddress) {
    write(actorId, action, entityType, entityId, before, after, ipAddress);
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize audit payload", e);
    }
  }
}
