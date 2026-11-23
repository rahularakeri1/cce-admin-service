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
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
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
  @Autowired private UserRoleRepository userRoleRepository;
  @SpyBean private UserRepository userRepository;

  private User user;

  @BeforeEach
  void createUser() {
    user = newUser(UserStatus.ACTIVE);
    Role superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
    userRoleRepository.save(new UserRole(user.getId(), superAdmin.getId()));
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
  void meMenusReturnsAllSevenSeededMenusInSidebarOrder() throws Exception {
    mockMvc
        .perform(get("/admin/v1/me/menus").with(token(user.getEmail(), Instant.now())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(7))
        .andExpect(jsonPath("$.data[0].label").value("Dashboard"))
        .andExpect(jsonPath("$.data[0].path").value("/"))
        .andExpect(jsonPath("$.data[1].label").value("Facilities"))
        .andExpect(jsonPath("$.data[4].path").value("/compliance/patients"))
        .andExpect(jsonPath("$.data[6].label").value("Ingestion"));
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
