package org.openphc.cce.admin.repository;

import java.util.List;
import org.openphc.cce.admin.entity.ApiPermission;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads/writes rows only — this service never owns this table's DDL (see entity Javadoc). */
public interface ApiPermissionRepository extends JpaRepository<ApiPermission, Long> {

  List<ApiPermission> findAllByPermissionName(String permissionName);

  void deleteAllByPermissionName(String permissionName);
}
