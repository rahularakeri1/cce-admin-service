package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.KeycloakGateway;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /users: filters, search, pagination, ordering. Every test row carries a unique facility tag so
 * assertions are isolated from whatever else is in the real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserListIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @MockBean private KeycloakGateway keycloak;

  private User actor;
  private Role roleA;
  private Role roleB;
  private String tag;
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tag = "TAG" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    actor = save("list-actor-" + UUID.randomUUID(), UserStatus.ACTIVE, "Actor", "Person", null);
    userRoleRepository.save(new UserRole(actor.getId(), roleRepository.findByName("SUPER_ADMIN").orElseThrow().getId()));
    roleA = newRole("TEST_LIST_A_");
    roleB = newRole("TEST_LIST_B_");
  }

  @AfterEach
  void cleanUp() {
    userIds.forEach(userRepository::deleteById);
    roleRepository.deleteById(roleA.getId());
    roleRepository.deleteById(roleB.getId());
  }

  private Role newRole(String prefix) {
    Role r = new Role();
    r.setId(UUID.randomUUID());
    r.setName(prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
    r.setDisplayName("List test");
    return roleRepository.save(r);
  }

  private User save(String username, UserStatus status, String first, String last, String facility) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(username);
    u.setEmail(username + "@example.org");
    u.setFirstName(first);
    u.setLastName(last);
    u.setStatus(status);
    u.setFacilityScope(facility);
    userIds.add(u.getId());
    return userRepository.save(u);
  }

  private User tagged(String name, UserStatus status, String first, String last, Role... roles) {
    User u = save(name + "-" + tag.toLowerCase(), status, first, last, tag);
    for (Role r : roles) {
      userRoleRepository.save(new UserRole(u.getId(), r.getId()));
    }
    return u;
  }

  private JwtRequestPostProcessor token() {
    return jwt().jwt(b -> b.claim("email", actor.getEmail()).claim("auth_time", Instant.now().getEpochSecond()));
  }

  private ResultActions list(String query) throws Exception {
    return mockMvc.perform(get("/admin/v1/users?facilityScope=" + tag + (query.isEmpty() ? "" : "&" + query)).with(token()));
  }

  @Test
  void listsUsersWithTheirRolesAndAPaginationBlock() throws Exception {
    tagged("alice", UserStatus.ACTIVE, "Alice", "Ng", roleA, roleB);
    tagged("bob", UserStatus.ACTIVE, "Bob", "Roe");

    list("")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.total").value(2))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.pageSize").value(20))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[?(@.firstName=='Alice')].roles.length()").value(org.hamcrest.Matchers.hasItem(2)))
        .andExpect(jsonPath("$.data[?(@.firstName=='Bob')].roles.length()").value(org.hamcrest.Matchers.hasItem(0)))
        .andExpect(jsonPath("$.data[0].password").doesNotExist());
  }

  @Test
  void filtersByStatus() throws Exception {
    tagged("act", UserStatus.ACTIVE, "A", "One");
    tagged("ina", UserStatus.INACTIVE, "I", "Two");

    list("status=INACTIVE").andExpect(jsonPath("$.pagination.total").value(1)).andExpect(jsonPath("$.data[0].status").value("INACTIVE"));
    list("status=active").andExpect(jsonPath("$.pagination.total").value(1)); // case-insensitive
  }

  @Test
  void filtersByRole() throws Exception {
    tagged("holds-a", UserStatus.ACTIVE, "Has", "RoleA", roleA);
    tagged("holds-b", UserStatus.ACTIVE, "Has", "RoleB", roleB);
    tagged("holds-both", UserStatus.ACTIVE, "Has", "Both", roleA, roleB);

    list("roleId=" + roleA.getId()).andExpect(jsonPath("$.pagination.total").value(2));
    list("roleId=" + roleB.getId()).andExpect(jsonPath("$.pagination.total").value(2));
    list("roleId=" + UUID.randomUUID()).andExpect(jsonPath("$.pagination.total").value(0));
  }

  @Test
  void searchMatchesUsernameEmailFirstAndLastNameCaseInsensitively() throws Exception {
    tagged("zed", UserStatus.ACTIVE, "Zoltan", "Kovacs");
    tagged("other", UserStatus.ACTIVE, "Mary", "Smith");

    list("q=zoltan").andExpect(jsonPath("$.pagination.total").value(1));
    list("q=KOVACS").andExpect(jsonPath("$.pagination.total").value(1));
    list("q=zed-" + tag.toLowerCase()).andExpect(jsonPath("$.pagination.total").value(1)); // username
    list("q=smith").andExpect(jsonPath("$.data[0].lastName").value("Smith"));
    list("q=%25").andExpect(jsonPath("$.pagination.total").value(0)); // a literal %, not a wildcard
    list("q=nomatchhere").andExpect(jsonPath("$.pagination.total").value(0));
  }

  @Test
  void paginatesOnTheServerNewestFirstWithoutOverlap() throws Exception {
    for (int i = 1; i <= 5; i++) {
      tagged("page" + i, UserStatus.ACTIVE, "P" + i, "User");
      Thread.sleep(5); // distinct created_at
    }

    String page0 = list("page=0&pageSize=2").andExpect(jsonPath("$.pagination.total").value(5)).andExpect(jsonPath("$.data.length()").value(2)).andReturn().getResponse().getContentAsString();
    String page2 = list("page=2&pageSize=2").andExpect(jsonPath("$.data.length()").value(1)).andReturn().getResponse().getContentAsString();
    list("page=1&pageSize=2").andExpect(jsonPath("$.data.length()").value(2));
    list("page=3&pageSize=2").andExpect(jsonPath("$.data.length()").value(0));

    assertThat(page0).contains("\"firstName\":\"P5\"").contains("\"firstName\":\"P4\""); // newest first
    assertThat(page2).contains("\"firstName\":\"P1\"");
  }

  @Test
  void rejectsBadPagingAndStatusParametersWith400() throws Exception {
    list("page=-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    list("pageSize=0").andExpect(status().isBadRequest());
    list("pageSize=101").andExpect(status().isBadRequest());
    list("status=bogus").andExpect(status().isBadRequest());
    list("roleId=not-a-uuid").andExpect(status().isBadRequest());
  }

  @Test
  void aNeverLoggedInActiveUserIsIdentifiableByANullLastLogin() throws Exception {
    User fresh = tagged("never", UserStatus.ACTIVE, "Never", "Seen");
    User seen = tagged("seen", UserStatus.ACTIVE, "Has", "LoggedIn");
    seen.setLastLoginAt(OffsetDateTime.now());
    userRepository.save(seen);

    list("q=never")
        .andExpect(jsonPath("$.data[0].id").value(fresh.getId().toString()))
        .andExpect(jsonPath("$.data[0].lastLoginAt").doesNotExist());
    list("q=loggedin").andExpect(jsonPath("$.data[0].lastLoginAt").exists());
  }

  @Test
  void requiresAToken() throws Exception {
    mockMvc.perform(get("/admin/v1/users")).andExpect(status().isUnauthorized());
  }
}
