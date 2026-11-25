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
  public UUID write(
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
    return row.getId();
  }

  /**
   * After a user-creation commit: records that the invitation email could not be sent by adding
   * {@code "inviteEmailSent": false} to that USER_CREATED row's {@code after} JSON, instead of
   * writing a separate audit action (docs/api-reference.md → POST /users). Own transaction: it runs
   * after the main one has committed.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markInviteEmailFailed(UUID auditRowId) {
    repository
        .findById(auditRowId)
        .ifPresent(
            row -> {
              try {
                com.fasterxml.jackson.databind.node.ObjectNode after =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(row.getAfter());
                after.put("inviteEmailSent", false);
                row.setAfter(objectMapper.writeValueAsString(after));
                repository.save(row);
              } catch (Exception e) {
                throw new IllegalStateException("Failed to update audit payload", e);
              }
            });
  }

  /**
   * Writes in its OWN, independent transaction — required whenever the caller's own transaction
   * is about to be rolled back but the write must survive it (e.g. USER_PROVISIONING_FAILED, see
   * docs/flow-diagrams.md Flow 2). REQUIRES_NEW suspends any existing transaction for this call.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID writeInNewTransaction(
      UUID actorId,
      AuditAction action,
      String entityType,
      String entityId,
      Object before,
      Object after,
      String ipAddress) {
    return write(actorId, action, entityType, entityId, before, after, ipAddress);
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
