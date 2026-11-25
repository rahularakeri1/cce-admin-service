package org.openphc.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;

/** Unit-tested with mocks: the live database already holds several SUPER_ADMINs, so "the last one" can't be staged there. */
class AdminLockoutGuardTest {

  private final RoleRepository roles = mock(RoleRepository.class);
  private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
  private final AdminLockoutGuard guard = new AdminLockoutGuard(roles, userRoles);
  private final UUID userId = UUID.randomUUID();
  private final UUID superAdminId = UUID.randomUUID();

  private void superAdminExists() {
    Role r = new Role();
    r.setId(superAdminId);
    r.setName("SUPER_ADMIN");
    when(roles.findByName("SUPER_ADMIN")).thenReturn(Optional.of(r));
  }

  @Test
  void refusesWhenTheUserIsTheOnlyActiveSuperAdmin() {
    superAdminExists();
    when(userRoles.existsByUserIdAndRoleId(userId, superAdminId)).thenReturn(true);
    when(userRoles.countActiveHolders(superAdminId)).thenReturn(1L);

    assertThatThrownBy(() -> guard.assertNotLastSuperAdmin(userId))
        .isInstanceOfSatisfying(ConflictException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("LAST_SUPER_ADMIN"));
  }

  @Test
  void allowsWhenAnotherActiveSuperAdminRemains() {
    superAdminExists();
    when(userRoles.existsByUserIdAndRoleId(userId, superAdminId)).thenReturn(true);
    when(userRoles.countActiveHolders(superAdminId)).thenReturn(2L);

    assertThatCode(() -> guard.assertNotLastSuperAdmin(userId)).doesNotThrowAnyException();
  }

  @Test
  void allowsWhenTheUserIsNotASuperAdminAtAll() {
    superAdminExists();
    when(userRoles.existsByUserIdAndRoleId(userId, superAdminId)).thenReturn(false);
    when(userRoles.countActiveHolders(superAdminId)).thenReturn(1L); // someone else is the only one — irrelevant

    assertThatCode(() -> guard.assertNotLastSuperAdmin(userId)).doesNotThrowAnyException();
  }

  @Test
  void allowsWhenThereIsNoSuperAdminRoleInTheDatabase() {
    when(roles.findByName("SUPER_ADMIN")).thenReturn(Optional.empty());

    assertThatCode(() -> guard.assertNotLastSuperAdmin(userId)).doesNotThrowAnyException();
  }
}
