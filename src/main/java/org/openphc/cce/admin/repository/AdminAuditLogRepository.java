package org.openphc.cce.admin.repository;

import java.util.UUID;
import org.openphc.cce.admin.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

  /** For test cleanup only — production never deletes audit rows (docs/data-dictionary.md §1.1). */
  @Transactional
  void deleteAllByActorId(UUID actorId);
}
