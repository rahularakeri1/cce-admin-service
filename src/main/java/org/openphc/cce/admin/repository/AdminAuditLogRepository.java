package org.openphc.cce.admin.repository;

import java.util.UUID;
import org.openphc.cce.admin.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {}
