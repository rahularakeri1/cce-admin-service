package org.openphc.cce.admin.service;

import java.util.UUID;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

/**
 * The first admin is created by hand (scripts/bootstrap-admin.sh), so losing the last active
 * SUPER_ADMIN leaves nobody able to administer the console and no UI path back. These checks refuse
 * the actions that would do that.
 */
@Component
public class AdminLockoutGuard {

  static final String SUPER_ADMIN = "SUPER_ADMIN";

  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;

  public AdminLockoutGuard(RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
    this.roleRepository = roleRepository;
    this.userRoleRepository = userRoleRepository;
  }

  /**
   * Call before an action that takes an ACTIVE user's SUPER_ADMIN access away (deactivating them, or
   * removing the role). A no-op unless that user currently holds it and is the only active holder.
   */
  public void assertNotLastSuperAdmin(UUID userId) {
    Role superAdmin = roleRepository.findByName(SUPER_ADMIN).orElse(null);
    if (superAdmin == null || !userRoleRepository.existsByUserIdAndRoleId(userId, superAdmin.getId())) {
      return;
    }
    if (userRoleRepository.countActiveHolders(superAdmin.getId()) <= 1) {
      throw ConflictException.lastSuperAdmin();
    }
  }
}
