package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.GatewaySyncResult;
import org.openphc.cce.admin.entity.ApiPermission;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.testsupport.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Permissions + gateway sync against the real ccedb, with gateway-service replaced by a mock so the
 * success / failure / partial paths are deterministic. The real gateway is exercised by hand in
 * Milestone 4's exit check and by Milestone 8.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RolePermissionEndpointIntegrationTest {

  private static final GatewaySyncResult OK = new GatewaySyncResult(8, 0, 0, List.of(), "ok");

  @Autowired private MockMvc mockMvc;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ApiPermissionRepository permissionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private AdminAuditLogRepository auditLogRepository;
  @MockBean private GatewaySyncClient gateway;

  private User actor;
  private final List<UUID> roleIds = new ArrayList<>();
  private final List<String> roleNames = new ArrayList<>();
  private final List<UUID> extraUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    reset(gateway);
    when(gateway.sync()).thenReturn(OK);
    actor = accounts().superAdmin("perm-test-actor");
  }

  @AfterEach
  void cleanUp() {
    extraUserIds.forEach(userRepository::deleteById);
    roleNames.forEach(permissionRepository::deleteAllByPermissionName);
    roleIds.forEach(id -> roleRepository.findById(id).ifPresent(roleRepository::delete));
    auditLogRepository.deleteAllByActorId(actor.getId());
    userRepository.deleteById(actor.getId());
  }

  private User newUser() {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername("perm-test-" + u.getId());
    u.setEmail("perm-test-" + u.getId() + "@example.org");
    u.setFirstName("Perm");
    u.setLastName("Test");
    u.setStatus(UserStatus.ACTIVE);
    return userRepository.save(u);
  }

  private TestAccounts accounts() {
    return new TestAccounts(userRepository, roleRepository, userRoleRepository);
  }

  private JwtRequestPostProcessor token() {
    return TestAccounts.tokenFor(actor);
  }

  private UUID createRole() throws Exception {
    String name = "TEST_PERM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    String body =
        mockMvc
            .perform(
                post("/admin/v1/roles")
                    .with(token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"displayName\":\"Perm test\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(body, "$.data.id"));
    roleIds.add(id);
    roleNames.add(name);
    return id;
  }

  private static String perm(String method, String uri, String description, String category) {
    return "{\"httpMethod\":\"" + method + "\",\"uriPattern\":\"" + uri + "\",\"description\":"
        + (description == null ? "null" : "\"" + description + "\"")
        + ",\"category\":" + (category == null ? "null" : "\"" + category + "\"") + "}";
  }

  private org.springframework.test.web.servlet.ResultActions putPermissions(UUID roleId, String... rows) throws Exception {
    return mockMvc.perform(
        put("/admin/v1/roles/" + roleId + "/permissions")
            .with(token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permissions\":[" + String.join(",", rows) + "]}"));
  }

  private String nameOf(UUID roleId) {
    return roleRepository.findById(roleId).map(Role::getName).orElseThrow();
  }

  private List<String> auditActions() {
    return auditLogRepository.findAll().stream()
        .filter(r -> actor.getId().equals(r.getActorId()))
        .map(r -> r.getAction())
        .toList();
  }

  @Test
  void savingWritesRowsUnderTheRolesNameThenSyncsAndReportsSynced() throws Exception {
    UUID id = createRole();

    putPermissions(
            id,
            perm("GET", "/v1/insights/compliance/**", "Read compliance", "COMPLIANCE"),
            perm("POST", "/v1/events/**", null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"))
        .andExpect(jsonPath("$.data.permissions.length()").value(2));

    List<ApiPermission> rows = permissionRepository.findAllByPermissionName(nameOf(id));
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(ApiPermission::getHttpMethod).containsExactlyInAnyOrder("GET", "POST");
    assertThat(rows.get(0).getCreatedAt()).isNotNull();
    verify(gateway, times(1)).sync();
    assertThat(auditActions()).contains("ROLE_PERMISSIONS_UPDATED");
  }

  @Test
  void getReturnsTheSavedRows() throws Exception {
    UUID id = createRole();
    putPermissions(id, perm("GET", "/v1/a/**", "d", "CAT")).andExpect(status().isOk());

    mockMvc
        .perform(get("/admin/v1/roles/" + id + "/permissions").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions.length()").value(1))
        .andExpect(jsonPath("$.data.permissions[0].httpMethod").value("GET"))
        .andExpect(jsonPath("$.data.permissions[0].uriPattern").value("/v1/a/**"))
        .andExpect(jsonPath("$.data.permissions[0].description").value("d"))
        .andExpect(jsonPath("$.data.permissions[0].category").value("CAT"))
        .andExpect(jsonPath("$.data.syncStatus").doesNotExist());
  }

  @Test
  void aSyncFailureReturns502ButTheRowsAndAuditStayCommitted() throws Exception {
    UUID id = createRole();
    when(gateway.sync()).thenThrow(new SyncFailedException("gateway down"));

    putPermissions(id, perm("GET", "/v1/keep/**", null, null))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));

    assertThat(permissionRepository.findAllByPermissionName(nameOf(id))).hasSize(1);
    assertThat(auditActions()).contains("ROLE_PERMISSIONS_UPDATED");
    mockMvc
        .perform(get("/admin/v1/roles/" + id + "/permissions").with(token()))
        .andExpect(jsonPath("$.data.permissions.length()").value(1));
  }

  @Test
  void reSyncRunsTheSyncWithoutTouchingRowsAndRecoversFromAFailure() throws Exception {
    UUID id = createRole();
    when(gateway.sync()).thenThrow(new SyncFailedException("down"));
    putPermissions(id, perm("GET", "/v1/x/**", null, null)).andExpect(status().isBadGateway());
    long rowsBefore = permissionRepository.findAllByPermissionName(nameOf(id)).size();
    int auditBefore = auditActions().size();

    doReturn(OK).when(gateway).sync(); // gateway is back (when(...) would re-throw the old stub)
    mockMvc
        .perform(post("/admin/v1/roles/" + id + "/sync").with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"));

    assertThat(permissionRepository.findAllByPermissionName(nameOf(id))).hasSize((int) rowsBefore);
    assertThat(auditActions()).hasSize(auditBefore); // re-sync changes no data, so no audit row
  }

  @Test
  void reSyncThatFailsAgainIs502() throws Exception {
    UUID id = createRole();
    when(gateway.sync()).thenThrow(new SyncFailedException("still down"));

    mockMvc
        .perform(post("/admin/v1/roles/" + id + "/sync").with(token()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));
  }

  @Test
  void aPartialSyncNamingThisRoleIsA502ButNamingOnlyOthersIsFine() throws Exception {
    UUID id = createRole();
    String name = nameOf(id);

    when(gateway.sync()).thenReturn(new GatewaySyncResult(7, 1, 0, List.of(name), "partial"));
    putPermissions(id, perm("GET", "/v1/p/**", null, null)).andExpect(status().isBadGateway());

    when(gateway.sync()).thenReturn(new GatewaySyncResult(7, 1, 0, List.of("SOME_OTHER_ROLE"), "partial"));
    putPermissions(id, perm("GET", "/v1/p/**", null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"));
  }

  @Test
  void replacingTheSetKeepsOverlapAndUpdatesItsTextAddsNewAndRemovesTheRest() throws Exception {
    UUID id = createRole();
    putPermissions(id, perm("GET", "/v1/one/**", "old", "A"), perm("GET", "/v1/two/**", null, null)).andExpect(status().isOk());
    Long keptId =
        permissionRepository.findAllByPermissionName(nameOf(id)).stream()
            .filter(p -> p.getUriPattern().equals("/v1/one/**"))
            .findFirst()
            .orElseThrow()
            .getId();

    putPermissions(id, perm("GET", "/v1/one/**", "new text", "B"), perm("DELETE", "/v1/three/**", null, null))
        .andExpect(status().isOk());

    List<ApiPermission> rows = permissionRepository.findAllByPermissionName(nameOf(id));
    assertThat(rows).extracting(p -> p.getHttpMethod() + " " + p.getUriPattern()).containsExactlyInAnyOrder("GET /v1/one/**", "DELETE /v1/three/**");
    ApiPermission kept = rows.stream().filter(p -> p.getUriPattern().equals("/v1/one/**")).findFirst().orElseThrow();
    assertThat(kept.getId()).isEqualTo(keptId); // same row, not delete + insert
    assertThat(kept.getDescription()).isEqualTo("new text");
    assertThat(kept.getResourceCategory()).isEqualTo("B");
  }

  @Test
  void duplicateMethodAndPatternIn_oneSaveIs400AndChangesNothing() throws Exception {
    UUID id = createRole();

    putPermissions(id, perm("GET", "/v1/dup/**", null, null), perm("GET", "/v1/dup/**", "again", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

    assertThat(permissionRepository.findAllByPermissionName(nameOf(id))).isEmpty();
    verify(gateway, never()).sync();
  }

  @Test
  void invalidMethodsAndPatternsAre400() throws Exception {
    UUID id = createRole();
    for (String bad :
        List.of(
            perm("FETCH", "/v1/a/**", null, null),
            perm("get", "/v1/a/**", null, null),
            perm("GET", "v1/no-leading-slash", null, null),
            perm("GET", "/has space", null, null),
            perm("GET", "", null, null))) {
      putPermissions(id, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    verify(gateway, never()).sync();
  }

  @Test
  void aSystemRolesPermissionsCannotBeEdited() throws Exception {
    UUID superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow().getId();
    long before = permissionRepository.findAllByPermissionName("SUPER_ADMIN").size();

    putPermissions(superAdmin, perm("GET", "/x/**", null, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_ROLE_IMMUTABLE"));

    assertThat(permissionRepository.findAllByPermissionName("SUPER_ADMIN")).hasSize((int) before);
    verify(gateway, never()).sync();
  }

  @Test
  void emptyingAHeldRolesPermissionsIs409ButEmptyingAnUnheldOneIsAllowed() throws Exception {
    UUID id = createRole();
    putPermissions(id, perm("GET", "/v1/held/**", null, null)).andExpect(status().isOk());
    User holder = newUser();
    extraUserIds.add(holder.getId());
    userRoleRepository.save(new UserRole(holder.getId(), id));

    putPermissions(id)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ROLE_IN_USE"));
    assertThat(permissionRepository.findAllByPermissionName(nameOf(id))).hasSize(1);

    userRepository.deleteById(holder.getId()); // nobody holds it any more
    putPermissions(id).andExpect(status().isOk());
    assertThat(permissionRepository.findAllByPermissionName(nameOf(id))).isEmpty();
  }

  @Test
  void permissionEndpointsOnAnUnknownRoleAre404() throws Exception {
    UUID nobody = UUID.randomUUID();
    mockMvc.perform(get("/admin/v1/roles/" + nobody + "/permissions").with(token())).andExpect(status().isNotFound());
    putPermissions(nobody, perm("GET", "/a", null, null)).andExpect(status().isNotFound());
    mockMvc.perform(post("/admin/v1/roles/" + nobody + "/sync").with(token())).andExpect(status().isNotFound());
    verify(gateway, never()).sync();
  }

  @Test
  void deletingARoleAlsoRemovesItsPermissionRowsAndTriggersASync() throws Exception {
    UUID id = createRole();
    String name = nameOf(id);
    putPermissions(id, perm("GET", "/v1/gone/**", null, null)).andExpect(status().isOk());
    org.mockito.Mockito.clearInvocations(gateway);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/admin/v1/roles/" + id).with(token()))
        .andExpect(status().isNoContent());

    assertThat(permissionRepository.findAllByPermissionName(name)).isEmpty();
    verify(gateway, times(1)).sync();
  }

  @Test
  void aFailedSyncAfterDeleteDoesNotFailTheDelete() throws Exception {
    UUID id = createRole();
    putPermissions(id, perm("GET", "/v1/gone/**", null, null)).andExpect(status().isOk());
    when(gateway.sync()).thenThrow(new SyncFailedException("down"));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/admin/v1/roles/" + id).with(token()))
        .andExpect(status().isNoContent());
    assertThat(roleRepository.existsById(id)).isFalse();
  }

  @Test
  void deletingARoleWithNoPermissionRowsDoesNotBotherGateway() throws Exception {
    UUID id = createRole();
    org.mockito.Mockito.clearInvocations(gateway);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/admin/v1/roles/" + id).with(token()))
        .andExpect(status().isNoContent());
    verify(gateway, never()).sync();
  }

  @Test
  void creatingARoleNamedLikeAnExistingGatewayPermissionIsReserved() throws Exception {
    for (String reserved : List.of("COMPLIANCE_READ", "INSIGHTS_READ", "HTTPBIN_WRITE")) {
      mockMvc
          .perform(
              post("/admin/v1/roles")
                  .with(token())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"" + reserved + "\",\"displayName\":\"x\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("ROLE_NAME_RESERVED"));
      assertThat(roleRepository.existsByName(reserved)).isFalse();
    }
  }

  @Test
  void permissionEndpointsRequireAToken() throws Exception {
    mockMvc.perform(get("/admin/v1/roles/" + UUID.randomUUID() + "/permissions")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/admin/v1/roles/" + UUID.randomUUID() + "/sync")).andExpect(status().isUnauthorized());
  }
}
