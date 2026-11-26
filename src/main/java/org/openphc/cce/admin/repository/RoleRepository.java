package org.openphc.cce.admin.repository;

import java.util.Optional;
import java.util.UUID;
import org.openphc.cce.admin.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

  Optional<Role> findByName(String name);

  boolean existsByName(String name);

  /** Names of the roles a user holds in user_roles — what authorization is decided on (not the token). */
  @org.springframework.data.jpa.repository.Query(
      "select r.name from Role r, UserRole ur where ur.roleId = r.id and ur.userId = :userId order by r.name")
  java.util.List<String> findNamesByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
