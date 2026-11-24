package org.openphc.cce.admin.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.openphc.cce.admin.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByUsernameIgnoreCase(String username);

  /** Only ever moves last_login_at forward — the SQL guard backs up the service's own pre-check. */
  @Modifying
  @Query(
      "update User u set u.lastLoginAt = :ts where u.id = :id"
          + " and (u.lastLoginAt is null or u.lastLoginAt < :ts)")
  int touchLastLogin(@Param("id") UUID id, @Param("ts") OffsetDateTime ts);
}
