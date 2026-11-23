package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Roles CRUD and role→menu assignment against the real local ccedb (V2 seed applied). Every test
 * creates uniquely-named roles and removes them — plus its own actor and audit rows — afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleEndpointIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MenuRepository menuRepository;
  @Autowired private RoleMenuRepository roleMenuRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private AdminAuditLogRepository auditLogRepository;

  private User actor;
  private final List<UUID> createdRoleIds = new ArrayList<>();
  private final List<UUID> extraUserIds = new ArrayList<>();

  @BeforeEach
  void createActor() {
    actor = newUser();
    Role superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
    userRoleRepository.save(new UserRole(actor.getId(), superAdmin.getId()));
  }

  @AfterEach
  void cleanUp() {
    extraUserIds.forEach(userRepository::deleteById); // user_roles rows cascade
    createdRoleIds.forEach(id -> roleRepository.findById(id).ifPresent(roleRepository::delete));
    auditLogRepository.deleteAllByActorId(actor.getId());
    userRepository.deleteById(actor.getId());
  }

  private User newUser() {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername("role-test-" + u.getId());
    u.setEmail("role-test-" + u.getId() + "@example.org");
    u.setFirstName("Role");
    u.setLastName("Test");
    u.setStatus(UserStatus.ACTIVE);
    return userRepository.save(u);
  }

  private JwtRequestPostProcessor token() {
    return jwt().jwt(b -> b.claim("email", actor.getEmail()).claim("auth_time", Instant.now().getEpochSecond()));
  }

  private String uniqueName() {
    return "TEST_ROLE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  /** Creates a role through the API and returns its id (registered for cleanup). */
  private UUID createRole(String name) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/admin/v1/roles")
                    .with(token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"displayName\":\"Test " + name + "\",\"description\":\"d\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(body, "$.data.id"));
    createdRoleIds.add(id);
    return id;
  }

  private List<UUID> seededMenuIds(int count) {
    return menuRepository.findAll(org.springframework.data.domain.Sort.by("sortOrder")).stream()
        .limit(count)
        .map(Menu::getId)
        .toList();
  }

  private String menusBody(List<UUID> ids) {
    return "{\"menuIds\":[" + String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList()) + "]}";
  }

  private List<String> auditActions() {
    return auditLogRepository.findAll().stream()
        .filter(row -> actor.getId().equals(row.getActorId()))
        .map(row -> row.getAction())
        .toList();
  }

  // ---- roles CRUD -----------------------------------------------------------------------------

  @Test
  void createdRoleIsReturnedWith201AndAppearsInTheList() throws Exception {
    String name = uniqueName();
    UUID id = createRole(name);

    mockMvc
        .perform(get("/admin/v1/roles").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name=='" + name + "')].id").value(hasItem(id.toString())))
        .andExpect(jsonPath("$.data[?(@.name=='" + name + "')].isSystem").value(hasItem(false)));
    mockMvc
        .perform(get("/admin/v1/roles/" + id).with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name));
  }

  @Test
  void listIncludesTheSeededSuperAdminAsASystemRole() throws Exception {
    mockMvc
        .perform(get("/admin/v1/roles").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name=='SUPER_ADMIN')].isSystem").value(hasItem(true)));
  }

  @Test
  void namesThatAreNotUpperSnakeCaseAreRejectedWith400() throws Exception {
    for (String bad :
        List.of("facility_admin", "Facility_Admin", "FACILITY ADMIN", "FACILITY-ADMIN", "_ADMIN", "ADMIN_", "FAC__ADMIN", "1ADMIN", "")) {
      mockMvc
          .perform(
              post("/admin/v1/roles")
                  .with(token())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"" + bad + "\",\"displayName\":\"x\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    assertThat(roleRepository.existsByName("facility_admin")).isFalse();
  }

  @Test
  void missingDisplayNameIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/admin/v1/roles")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + uniqueName() + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void malformedJsonIsA400NotA500() throws Exception {
    mockMvc
        .perform(post("/admin/v1/roles").with(token()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void duplicateNameIs409() throws Exception {
    String name = uniqueName();
    createRole(name);

    mockMvc
        .perform(
            post("/admin/v1/roles")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"displayName\":\"again\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_ROLE_NAME"));
  }

  @Test
  void updateChangesDisplayNameAndDescriptionAndIgnoresANameInTheBody() throws Exception {
    String name = uniqueName();
    UUID id = createRole(name);

    mockMvc
        .perform(
            put("/admin/v1/roles/" + id)
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HIJACKED_NAME\",\"displayName\":\"Renamed Display\",\"description\":\"new desc\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name))
        .andExpect(jsonPath("$.data.displayName").value("Renamed Display"))
        .andExpect(jsonPath("$.data.description").value("new desc"));
    assertThat(roleRepository.existsByName("HIJACKED_NAME")).isFalse();
  }

  @Test
  void updatingASystemRoleIs409() throws Exception {
    UUID superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow().getId();

    mockMvc
        .perform(
            put("/admin/v1/roles/" + superAdmin)
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Nope\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_ROLE_IMMUTABLE"));
  }

  @Test
  void deletingSuperAdminIs409SystemRoleImmutableNotAForeignKeyError() throws Exception {
    UUID superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow().getId();

    mockMvc
        .perform(delete("/admin/v1/roles/" + superAdmin).with(token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_ROLE_IMMUTABLE"));
    assertThat(roleRepository.existsById(superAdmin)).isTrue();
  }

  @Test
  void deletingARoleThatUsersStillHoldIs409RoleInUse() throws Exception {
    UUID id = createRole(uniqueName());
    User holder = newUser();
    extraUserIds.add(holder.getId());
    userRoleRepository.save(new UserRole(holder.getId(), id));

    mockMvc
        .perform(delete("/admin/v1/roles/" + id).with(token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ROLE_IN_USE"));
    assertThat(roleRepository.existsById(id)).isTrue();
  }

  @Test
  void deletingAFreeRoleIs204AndRemovesItsMenuAssignmentsToo() throws Exception {
    UUID id = createRole(uniqueName());
    mockMvc
        .perform(
            put("/admin/v1/roles/" + id + "/menus")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(menusBody(seededMenuIds(2))))
        .andExpect(status().isOk());
    assertThat(roleMenuRepository.findAllByRoleId(id)).hasSize(2);

    mockMvc.perform(delete("/admin/v1/roles/" + id).with(token())).andExpect(status().isNoContent());

    assertThat(roleRepository.existsById(id)).isFalse();
    assertThat(roleMenuRepository.findAllByRoleId(id)).isEmpty();
    mockMvc.perform(get("/admin/v1/roles/" + id).with(token())).andExpect(status().isNotFound());
  }

  @Test
  void unknownRoleIsA404AndAnInvalidIdIsA400() throws Exception {
    mockMvc
        .perform(get("/admin/v1/roles/" + UUID.randomUUID()).with(token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ROLE_NOT_FOUND"));
    mockMvc
        .perform(get("/admin/v1/roles/not-a-uuid").with(token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  // ---- role → menus ---------------------------------------------------------------------------

  @Test
  void aSavedMenuSetRoundTripsAndASecondIdenticalSaveStillSucceedsAndAudits() throws Exception {
    UUID id = createRole(uniqueName());
    List<UUID> two = seededMenuIds(2);

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              put("/admin/v1/roles/" + id + "/menus")
                  .with(token())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(menusBody(two)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.menuIds.length()").value(2));
    }

    mockMvc
        .perform(get("/admin/v1/roles/" + id + "/menus").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.menuIds.length()").value(2))
        .andExpect(jsonPath("$.data.menuIds").value(hasItem(two.get(0).toString())))
        .andExpect(jsonPath("$.data.menuIds").value(hasItem(two.get(1).toString())));
    assertThat(auditActions()).filteredOn("ROLE_MENUS_UPDATED"::equals).hasSize(2);
  }

  @Test
  void replacingTheSetKeepsTheOverlapAddsNewAndRemovesTheRest() throws Exception {
    UUID id = createRole(uniqueName());
    List<UUID> menus = seededMenuIds(4);

    saveMenus(id, List.of(menus.get(0), menus.get(1)));
    saveMenus(id, List.of(menus.get(1), menus.get(2), menus.get(3)));

    assertThat(roleMenuRepository.findAllByRoleId(id))
        .extracting(rm -> rm.getMenuId())
        .containsExactlyInAnyOrder(menus.get(1), menus.get(2), menus.get(3));
  }

  @Test
  void anEmptySetClearsTheRolesMenus() throws Exception {
    UUID id = createRole(uniqueName());
    saveMenus(id, seededMenuIds(3));
    saveMenus(id, List.of());

    assertThat(roleMenuRepository.findAllByRoleId(id)).isEmpty();
  }

  @Test
  void anUnknownMenuIdIs404AndLeavesTheExistingSetUntouched() throws Exception {
    UUID id = createRole(uniqueName());
    List<UUID> two = seededMenuIds(2);
    saveMenus(id, two);

    mockMvc
        .perform(
            put("/admin/v1/roles/" + id + "/menus")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(menusBody(List.of(two.get(0), UUID.randomUUID()))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("MENU_NOT_FOUND"));

    assertThat(roleMenuRepository.findAllByRoleId(id)).hasSize(2);
  }

  @Test
  void menusOfAnUnknownRoleIs404() throws Exception {
    mockMvc
        .perform(get("/admin/v1/roles/" + UUID.randomUUID() + "/menus").with(token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ROLE_NOT_FOUND"));
  }

  private void saveMenus(UUID roleId, List<UUID> menuIds) throws Exception {
    mockMvc
        .perform(
            put("/admin/v1/roles/" + roleId + "/menus")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(menusBody(menuIds)))
        .andExpect(status().isOk());
  }

  // ---- audit + catalog ------------------------------------------------------------------------

  @Test
  void everyMutationWritesOneAuditRow() throws Exception {
    UUID id = createRole(uniqueName());
    mockMvc
        .perform(
            put("/admin/v1/roles/" + id)
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Changed\"}"))
        .andExpect(status().isOk());
    saveMenus(id, seededMenuIds(1));
    mockMvc.perform(delete("/admin/v1/roles/" + id).with(token())).andExpect(status().isNoContent());

    assertThat(auditActions())
        .containsExactlyInAnyOrder("ROLE_CREATED", "ROLE_UPDATED", "ROLE_MENUS_UPDATED", "ROLE_DELETED");
  }

  @Test
  void menuCatalogListsAllSevenSeededMenusInOrderWithTheirKeys() throws Exception {
    mockMvc
        .perform(get("/admin/v1/menus").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(7))
        .andExpect(jsonPath("$.data[0].menuKey").value("dashboard"))
        .andExpect(jsonPath("$.data[0].isActive").value(true))
        .andExpect(jsonPath("$.data[6].menuKey").value("ingestion"))
        .andExpect(jsonPath("$.data[*].menuKey").value(not(hasItem("nope"))));
  }

  @Test
  void roleEndpointsRequireAToken() throws Exception {
    mockMvc.perform(get("/admin/v1/roles")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/v1/menus")).andExpect(status().isUnauthorized());
  }
}
