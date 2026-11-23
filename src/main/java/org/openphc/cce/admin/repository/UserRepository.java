package org.openphc.cce.admin.repository;

import java.util.Optional;
import java.util.UUID;
import org.openphc.cce.admin.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
