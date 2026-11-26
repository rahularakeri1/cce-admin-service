package org.openphc.cce.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.testsupport.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Service-level authorization (Milestone 7): every /admin/v1/** endpoint but /me, /me/menus and /health needs
 * SUPER_ADMIN, decided on the caller's DATABASE roles. MockMvc bypasses the gateway entirely, so every 403
 * here comes from the service itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationIntegrationTest {

  private static final List<String> MANAGEMENT_PATHS =
      List.of("/admin/v1/users", "/admin/v1/roles", "/admin/v1/menus");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRoleRepository userRoleRepository;

  private TestAccounts accounts;
  private final List<UUID> userIds = new ArrayList<>();
  private final List<UUID> roleIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    accounts = new TestAccounts(userRepository, roleRepository, userRoleRepository);
  }

  @AfterEach
  void cleanUp() {
    userIds.forEach(userRepository::deleteById); // user_roles rows cascade
    roleIds.forEach(roleRepository::deleteById);
  }

  private User track(User user) {
    userIds.add(user.getId());
    return user;
  }

  private Role newRole() {
    Role r = new Role();
    r.setId(UUID.randomUUID());
    r.setName("TEST_AUTHZ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
    r.setDisplayName("Authz test");
    roleIds.add(r.getId());
    return roleRepository.save(r);
  }

  @Test
  void aRolelessUserIsRefusedEveryManagementEndpointButMayReadItsOwnProfileAndMenus() throws Exception {
    User roleless = track(accounts.activeUser("authz-roleless"));

    for (String path : MANAGEMENT_PATHS) {
      mockMvc
          .perform(get(path).with(TestAccounts.tokenFor(roleless)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    mockMvc.perform(get("/admin/v1/me").with(TestAccounts.tokenFor(roleless))).andExpect(status().isOk());
    mockMvc.perform(get("/admin/v1/me/menus").with(TestAccounts.tokenFor(roleless))).andExpect(status().isOk());
    mockMvc
        .perform(get("/admin/v1/me/menus").param("console", "ADMIN").with(TestAccounts.tokenFor(roleless)))
        .andExpect(status().isOk());
  }

  @Test
  void writesAreRefusedToo() throws Exception {
    User roleless = track(accounts.activeUser("authz-write"));

    mockMvc
        .perform(
            post("/admin/v1/roles")
                .with(TestAccounts.tokenFor(roleless))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SNEAKY\",\"displayName\":\"x\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/admin/v1/users").with(TestAccounts.tokenFor(roleless)).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aUserWithSomeOtherRoleIsRefusedToo() throws Exception {
    Role custom = newRole();
    User holder = track(accounts.activeUser("authz-other"));
    userRoleRepository.save(new org.openphc.cce.admin.entity.UserRole(holder.getId(), custom.getId()));

    mockMvc.perform(get("/admin/v1/users").with(TestAccounts.tokenFor(holder))).andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/v1/roles").with(TestAccounts.tokenFor(holder))).andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/v1/me").with(TestAccounts.tokenFor(holder))).andExpect(status().isOk());
  }

  @Test
  void superAdminIsUnchanged() throws Exception {
    User admin = track(accounts.superAdmin("authz-admin"));

    for (String path : MANAGEMENT_PATHS) {
      mockMvc.perform(get(path).with(TestAccounts.tokenFor(admin))).andExpect(status().isOk());
    }
  }

  @Test
  void aRoleChangeTakesEffectOnTheVeryNextRequest() throws Exception {
    User user = track(accounts.activeUser("authz-change"));
    JwtRequestPostProcessor sameToken = TestAccounts.tokenFor(user); // one token, never refreshed

    mockMvc.perform(get("/admin/v1/users").with(sameToken)).andExpect(status().isForbidden());

    accounts.grant(user, "SUPER_ADMIN");
    mockMvc.perform(get("/admin/v1/users").with(sameToken)).andExpect(status().isOk());

    userRoleRepository.deleteAll(userRoleRepository.findAllByUserId(user.getId()));
    mockMvc.perform(get("/admin/v1/users").with(sameToken)).andExpect(status().isForbidden());
  }

  @Test
  void theTokensOwnRoleClaimIsNotWhatDecidesAccess() throws Exception {
    User roleless = track(accounts.activeUser("authz-claim"));
    JwtRequestPostProcessor claimsSuperAdmin =
        jwt().jwt(b -> b.claim("email", roleless.getEmail()).claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN"))).claim("auth_time", Instant.now().getEpochSecond()));

    mockMvc.perform(get("/admin/v1/users").with(claimsSuperAdmin)).andExpect(status().isForbidden());
  }

  @Test
  void anInactiveSuperAdminIsRefused() throws Exception {
    User inactive = track(accounts.user("authz-inactive", UserStatus.INACTIVE));
    accounts.grant(inactive, "SUPER_ADMIN");

    mockMvc.perform(get("/admin/v1/users").with(TestAccounts.tokenFor(inactive))).andExpect(status().isForbidden());
  }

  @Test
  void noTokenIsStill401AndHealthStaysOpen() throws Exception {
    mockMvc.perform(get("/admin/v1/users")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/v1/roles")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/v1/me")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/v1/health")).andExpect(status().isOk());
  }
}
