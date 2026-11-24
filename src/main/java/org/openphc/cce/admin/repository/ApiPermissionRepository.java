package org.openphc.cce.admin.repository;

import java.util.List;
import org.openphc.cce.admin.entity.ApiPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Reads/writes rows only — this service never owns this table's DDL (see entity Javadoc). */
public interface ApiPermissionRepository extends JpaRepository<ApiPermission, Long> {

  List<ApiPermission> findAllByPermissionName(String permissionName);

  boolean existsByPermissionName(String permissionName);

  @Transactional
  void deleteAllByPermissionName(String permissionName);
}
