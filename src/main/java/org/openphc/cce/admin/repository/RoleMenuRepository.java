package org.openphc.cce.admin.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.RoleMenuId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, RoleMenuId> {

  List<RoleMenu> findAllByRoleId(UUID roleId);

  List<RoleMenu> findAllByRoleIdIn(Collection<UUID> roleIds);

  long countByMenuId(UUID menuId);

  void deleteAllByRoleId(UUID roleId);

  void deleteAllByMenuId(UUID menuId);
}
