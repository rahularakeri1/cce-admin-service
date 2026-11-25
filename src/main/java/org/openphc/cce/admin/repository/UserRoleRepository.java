package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

  List<UserRole> findAllByUserId(UUID userId);

  boolean existsByRoleId(UUID roleId);

  boolean existsByUserIdAndRoleId(UUID userId, UUID roleId);

  /** ACTIVE users holding the role — used to refuse removing the last SUPER_ADMIN. */
  @org.springframework.data.jpa.repository.Query(
      "select count(ur) from UserRole ur, User u where u.id = ur.userId and ur.roleId = :roleId"
          + " and u.status = org.openphc.cce.admin.entity.UserStatus.ACTIVE")
  long countActiveHolders(@org.springframework.data.repository.query.Param("roleId") UUID roleId);

  List<UserRole> findAllByUserIdIn(java.util.Collection<UUID> userIds);

  void deleteAllByUserId(UUID userId);
}
