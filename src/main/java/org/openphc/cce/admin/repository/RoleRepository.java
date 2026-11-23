package org.openphc.cce.admin.repository;

import java.util.Optional;
import java.util.UUID;
import org.openphc.cce.admin.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

  Optional<Role> findByName(String name);

  boolean existsByName(String name);
}
