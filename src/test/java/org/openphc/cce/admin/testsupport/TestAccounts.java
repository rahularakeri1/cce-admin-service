package org.openphc.cce.admin.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.UUID;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * Makes the local accounts and tokens the integration tests call the API as. Since Milestone 7 the service
 * decides access on the caller's database roles, so a test "admin" must really hold SUPER_ADMIN in
 * user_roles — a token with only an email claim is now a roleless user and gets 403 on management endpoints.
 *
 * <p>Deleting a user cascades to its user_roles rows; audit rows the user wrote must be removed first.
 */
public class TestAccounts {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;

  public TestAccounts(UserRepository userRepository, RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.userRoleRepository = userRoleRepository;
  }

  /** An ACTIVE user with no roles. */
  public User activeUser(String prefix) {
    return user(prefix, UserStatus.ACTIVE);
  }

  public User user(String prefix, UserStatus status) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(prefix + "-" + u.getId());
    u.setEmail(u.getUsername() + "@example.org");
    u.setFirstName("Test");
    u.setLastName("Account");
    u.setStatus(status);
    return userRepository.save(u);
  }

  /** An ACTIVE user holding the seeded SUPER_ADMIN role. */
  public User superAdmin(String prefix) {
    User u = activeUser(prefix);
    grant(u, "SUPER_ADMIN");
    return u;
  }

  public void grant(User user, String roleName) {
    Role role = roleRepository.findByName(roleName).orElseThrow();
    userRoleRepository.save(new UserRole(user.getId(), role.getId()));
  }

  /** A token as Keycloak would issue it for this user: the email is what the service resolves it by. */
  public static JwtRequestPostProcessor tokenFor(User user) {
    return jwt().jwt(b -> b.claim("email", user.getEmail()).claim("auth_time", Instant.now().getEpochSecond()));
  }
}
