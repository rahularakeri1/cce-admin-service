package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.testsupport.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /me and GET /me/menus against the real local ccedb, with the V2 seed data (7 menus,
 * SUPER_ADMIN) already applied by Flyway at context start. Each test creates and removes its own
 * user, so nothing is left behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeEndpointIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MenuRepository menuRepository;
  @Autowired private RoleMenuRepository roleMenuRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @SpyBean private UserRepository userRepository;

  private User user;

  @BeforeEach
  void createUser() {
    user = new TestAccounts(userRepository, roleRepository, userRoleRepository).superAdmin("me-test");
  }

  @AfterEach
  void deleteUser() {
    // user_roles rows go with the user (ON DELETE CASCADE).
    userRepository.deleteById(user.getId());
  }

  private User newUser(UserStatus status) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername("me-test-" + u.getId());
    u.setEmail("me-test-" + u.getId() + "@example.org");
    u.setFirstName("Me");
    u.setLastName("Test");
    u.setStatus(status);
    return userRepository.save(u);
  }

  private JwtRequestPostProcessor token(String email, Instant authTime) {
    return jwt()
        .jwt(
            builder -> {
              builder.claim("email", email);
              if (authTime != null) {
                builder.claim("auth_time", authTime.getEpochSecond());
              }
            });
  }

  @Test
  void meReturnsTheLocalProfileAndRoleNames() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
        .andExpect(jsonPath("$.data.username").value(user.getUsername()))
        .andExpect(jsonPath("$.data.roles[0]").value("SUPER_ADMIN"))
        .andExpect(jsonPath("$.data.roles.length()").value(1));
  }

  @Test
  void meMenusReturnsTheSevenSeededMenus() throws Exception {
    // Not their order or the catalog's size: admins arrange the menus and may add their own, and this runs against the
    // shared local database. What must hold is that SUPER_ADMIN holds the seven seeded insights menus, and only insights.
    mockMvc
        .perform(get("/admin/v1/me/menus").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[*].label")
                .value(org.hamcrest.Matchers.hasItems("Dashboard", "Facilities", "Compliance", "Deviations", "Patients", "Events", "Ingestion")))
        .andExpect(jsonPath("$.data[*].path").value(org.hamcrest.Matchers.hasItems("/", "/compliance/patients")))
        .andExpect(jsonPath("$.data[*].path").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("/users"))));
  }

  @Test
  void consoleAdminReturnsTheFourAdminTabsForSuperAdmin() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me/menus").param("console", "ADMIN").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4)) // the admin tabs only ever come from a migration
        .andExpect(jsonPath("$.data[*].path").value(org.hamcrest.Matchers.containsInAnyOrder("/users", "/roles", "/menus", "/protocols")))
        .andExpect(jsonPath("$.data[*].label").value(org.hamcrest.Matchers.containsInAnyOrder("Users", "Roles", "Menus", "Protocols")));
  }

  @Test
  void consoleInsightsIsTheExplicitFormOfTheDefault() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me/menus").param("console", "INSIGHTS").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].label").value(org.hamcrest.Matchers.hasItems("Dashboard", "Ingestion")));
  }

  @Test
  void anUnknownConsoleIs400() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me/menus").param("console", "nope").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aRoleHoldingOnlyTheProtocolsTabGetsJustThatTabAndNoInsightsMenus() throws Exception {
    Role custom = new Role();
    custom.setId(UUID.randomUUID());
    custom.setName("TEST_ME_PROTO_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
    custom.setDisplayName("Me test");
    roleRepository.save(custom);
    User holder = newUser(UserStatus.ACTIVE);
    try {
      userRoleRepository.save(new UserRole(holder.getId(), custom.getId()));
      UUID protocols = menuRepository.findAll().stream().filter(m -> m.getMenuKey().equals("admin-protocols")).findFirst().orElseThrow().getId();
      roleMenuRepository.save(new RoleMenu(custom.getId(), protocols));

      mockMvc
          .perform(get("/admin/v1/me/menus").param("console", "ADMIN").with(token(holder.getEmail(), Instant.now())))
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].path").value("/protocols"));
      mockMvc
          .perform(get("/admin/v1/me/menus").with(token(holder.getEmail(), Instant.now())))
          .andExpect(jsonPath("$.data.length()").value(0));

      // Untick it: the very next call sees nothing (no token refresh involved).
      roleMenuRepository.deleteAll(roleMenuRepository.findAllByRoleId(custom.getId()));
      mockMvc
          .perform(get("/admin/v1/me/menus").param("console", "ADMIN").with(token(holder.getEmail(), Instant.now())))
          .andExpect(jsonPath("$.data.length()").value(0));
    } finally {
      userRepository.deleteById(holder.getId());
      roleRepository.deleteById(custom.getId());
    }
  }

  @Test
  void userWithNoRolesGetsNoMenus() throws Exception {
    User roleless = newUser(UserStatus.ACTIVE);
    try {
      mockMvc
          .perform(get("/admin/v1/me/menus").with(token(roleless.getEmail(), Instant.now())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(0));
    } finally {
      userRepository.deleteById(roleless.getId());
    }
  }

  @Test
  void tokenForAnEmailWithNoLocalUserIsForbidden() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me").with(token("nobody-" + UUID.randomUUID() + "@example.org", Instant.now())))
        .andExpect(status().isForbidden());
  }

  @Test
  void inactiveUserIsForbidden() throws Exception {
    User inactive = newUser(UserStatus.INACTIVE);
    try {
      mockMvc
          .perform(get("/admin/v1/me").with(token(inactive.getEmail(), Instant.now())))
          .andExpect(status().isForbidden());
    } finally {
      userRepository.deleteById(inactive.getId());
    }
  }

  @Test
  void firstMeCallSetsLastLoginFromAuthTimeAndASecondCallDoesNotWriteAgain() throws Exception {
    Instant authTime = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), authTime))).andExpect(status().isOk());
    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), authTime))).andExpect(status().isOk());

    OffsetDateTime stored = userRepository.findById(user.getId()).orElseThrow().getLastLoginAt();
    assertThat(stored.toInstant()).isEqualTo(authTime);
    verify(userRepository, times(1)).touchLastLogin(eq(user.getId()), any());
  }

  @Test
  void aNewerLoginUpdatesLastLoginEvenSoonAfterTheLastOne() throws Exception {
    Instant first = Instant.now().minus(3, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    Instant second = first.plusSeconds(90);

    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), first))).andExpect(status().isOk());
    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), second))).andExpect(status().isOk());

    OffsetDateTime stored = userRepository.findById(user.getId()).orElseThrow().getLastLoginAt();
    assertThat(stored.toInstant()).isEqualTo(second);
  }

  @Test
  void withoutAuthTimeLastLoginFallsBackToNowAndIsThrottled() throws Exception {
    Instant before = Instant.now().minusSeconds(1);

    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), null))).andExpect(status().isOk());
    mockMvc.perform(get("/admin/v1/me").with(token(user.getEmail(), null))).andExpect(status().isOk());

    OffsetDateTime stored = userRepository.findById(user.getId()).orElseThrow().getLastLoginAt();
    assertThat(stored.toInstant()).isAfterOrEqualTo(before);
    verify(userRepository, times(1)).touchLastLogin(eq(user.getId()), any());
  }
}
