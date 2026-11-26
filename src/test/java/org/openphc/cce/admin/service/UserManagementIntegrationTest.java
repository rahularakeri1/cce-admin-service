package org.openphc.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.KeycloakGateway;
import org.openphc.cce.admin.client.KeycloakUserProfile;
import org.openphc.cce.admin.entity.AdminAuditLog;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.testsupport.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/**
 * Fetch / edit / status / roles / reset-password against the real ccedb with Keycloak faked. In this
 * package so the commit seam of UserManagementService can be made to fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserManagementIntegrationTest {

  private static final String KC_ID = "kc-target";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private AdminAuditLogRepository auditLogRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockBean private KeycloakGateway keycloak;
  @SpyBean private UserManagementService managementService;
  @SpyBean private AdminLockoutGuard lockoutGuard;

  private User actor;
  private User target;
  private final List<Role> roles = new ArrayList<>();
  private final List<UUID> extraUsers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    reset(keycloak);
    when(keycloak.findUserId(anyString())).thenReturn(KC_ID);
    when(keycloak.missingRealmRoles(anyCollection())).thenReturn(List.of());
    when(keycloak.updateUser(anyString(), any())).thenReturn(new KeycloakUserProfile("Old", "Person", "old@example.org", true));

    actor = new TestAccounts(userRepository, roleRepository, userRoleRepository).superAdmin("mgmt-actor");
    target = newUser("target", UserStatus.ACTIVE);
    for (int i = 0; i < 6; i++) {
      Role r = new Role();
      r.setId(UUID.randomUUID());
      r.setName("TEST_MGMT_" + i + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
      r.setDisplayName("Mgmt test");
      roles.add(roleRepository.save(r));
    }
  }

  @AfterEach
  void cleanUp() {
    extraUsers.forEach(userRepository::deleteById);
    auditLogRepository.deleteAllByActorId(actor.getId());
    userRepository.deleteById(target.getId());
    userRepository.deleteById(actor.getId());
    roles.forEach(r -> roleRepository.deleteById(r.getId()));
  }

  private User newUser(String tag, UserStatus status) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername("mgmt-" + tag + "-" + u.getId().toString().substring(0, 8));
    u.setEmail(u.getUsername() + "@example.org");
    u.setFirstName("First");
    u.setLastName("Last");
    u.setStatus(status);
    u.setFacilityScope("Kigali");
    return userRepository.save(u);
  }

  private void give(User user, Role... rs) {
    for (Role r : rs) userRoleRepository.save(new UserRole(user.getId(), r.getId()));
  }

  private JwtRequestPostProcessor token() {
    return TestAccounts.tokenFor(actor);
  }

  private ResultActions callPut(String path, String json) throws Exception {
    return mockMvc.perform(put(path).with(token()).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions callPatch(String path, String json) throws Exception {
    return mockMvc.perform(patch(path).with(token()).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions callPost(String path) throws Exception {
    return mockMvc.perform(post(path).with(token()));
  }

  private List<AdminAuditLog> audit() {
    return auditLogRepository.findAll().stream().filter(r -> actor.getId().equals(r.getActorId())).toList();
  }

  private List<String> auditActions() {
    return audit().stream().map(AdminAuditLog::getAction).toList();
  }

  private String profile(String first, String last, String email, String facility) {
    return "{\"firstName\":\"" + first + "\",\"lastName\":\"" + last + "\",\"email\":\"" + email + "\",\"facilityScope\":" + (facility == null ? "null" : "\"" + facility + "\"") + "}";
  }

  private String roleIds(Role... rs) {
    return "{\"roleIds\":[" + String.join(",", java.util.Arrays.stream(rs).map(r -> "\"" + r.getId() + "\"").toList()) + "]}";
  }

  private User reload(User u) {
    return userRepository.findById(u.getId()).orElseThrow();
  }

  private List<String> roleNamesOf(User u) {
    return userRoleRepository.findAllByUserId(u.getId()).stream().map(l -> roleRepository.findById(l.getRoleId()).orElseThrow().getName()).sorted().toList();
  }

  // ---- GET ------------------------------------------------------------------------------------

  @Test
  void fetchReturnsTheUserWithRolesAndTheRightErrorsForBadIds() throws Exception {
    give(target, roles.get(0), roles.get(1));

    mockMvc
        .perform(get("/admin/v1/users/" + target.getId()).with(token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value(target.getUsername()))
        .andExpect(jsonPath("$.data.roles.length()").value(2));
    mockMvc.perform(get("/admin/v1/users/" + UUID.randomUUID()).with(token())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    mockMvc.perform(get("/admin/v1/users/not-a-uuid").with(token())).andExpect(status().isBadRequest());
  }

  // ---- PUT profile ----------------------------------------------------------------------------

  @Test
  void editingProfileFieldsUpdatesLocalAndKeycloakAndAuditsWithoutReverifyingAnUnchangedEmail() throws Exception {
    callPut("/admin/v1/users/" + target.getId(), profile("Mira", "Live", target.getEmail(), "Huye"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.firstName").value("Mira"))
        .andExpect(jsonPath("$.data.facilityScope").value("Huye"))
        .andExpect(jsonPath("$.warnings").doesNotExist());

    User saved = reload(target);
    assertThat(saved.getFirstName()).isEqualTo("Mira");
    assertThat(saved.getFacilityScope()).isEqualTo("Huye");
    verify(keycloak).findUserId(target.getUsername()); // looked up by the immutable username
    verify(keycloak).updateUser(KC_ID, new KeycloakUserProfile("Mira", "Live", target.getEmail(), null)); // null = leave the verified flag alone
    verify(keycloak, never()).sendActionsEmail(any(), any(), anyLong());
    assertThat(auditActions()).containsExactly("USER_UPDATED");
    assertThat(audit().get(0).getBefore()).contains("Kigali");
    assertThat(audit().get(0).getAfter()).contains("Huye");
  }

  @Test
  void changingTheEmailLowerCasesItMarksItUnverifiedAndSendsAVerificationAfterTheCommit() throws Exception {
    callPut("/admin/v1/users/" + target.getId(), profile("First", "Last", "New.Address@Example.ORG", "Kigali"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("new.address@example.org"));

    assertThat(reload(target).getEmail()).isEqualTo("new.address@example.org");
    verify(keycloak).updateUser(KC_ID, new KeycloakUserProfile("First", "Last", "new.address@example.org", Boolean.FALSE));
    verify(keycloak, times(1)).sendActionsEmail(eq(KC_ID), eq(List.of("VERIFY_EMAIL")), eq(259200L));
  }

  @Test
  void aFailedVerificationEmailAfterAnEmailChangeIsAWarningNotAnError() throws Exception {
    doThrow(new SyncFailedException("smtp down")).when(keycloak).sendActionsEmail(any(), any(), anyLong());

    callPut("/admin/v1/users/" + target.getId(), profile("First", "Last", "changed@example.org", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warnings[0]").value(containsString("verification email could not be sent")));
    assertThat(reload(target).getEmail()).isEqualTo("changed@example.org");
  }

  @Test
  void anEmailAlreadyUsedByAnotherUserIs409WithZeroKeycloakCalls() throws Exception {
    User other = newUser("other", UserStatus.ACTIVE);
    extraUsers.add(other.getId());

    callPut("/admin/v1/users/" + target.getId(), profile("First", "Last", other.getEmail().toUpperCase(), null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"));

    verifyNoInteractions(keycloak);
    assertThat(reload(target).getEmail()).isEqualTo(target.getEmail());
  }

  @Test
  void keycloakFailingDuringAProfileEditRollsBackTheLocalChange() throws Exception {
    doThrow(new SyncFailedException("keycloak down")).when(keycloak).updateUser(any(), any());

    callPut("/admin/v1/users/" + target.getId(), profile("Changed", "Name", target.getEmail(), "Elsewhere"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));

    User saved = reload(target);
    assertThat(saved.getFirstName()).isEqualTo("First");
    assertThat(saved.getFacilityScope()).isEqualTo("Kigali");
    assertThat(audit()).isEmpty(); // the USER_UPDATED row rolled back with it
  }

  @Test
  void invalidProfilesAre400AndUnknownUsersAre404() throws Exception {
    callPut("/admin/v1/users/" + target.getId(), profile("", "Last", target.getEmail(), null)).andExpect(status().isBadRequest());
    callPut("/admin/v1/users/" + target.getId(), profile("First", "Last", "not-an-email", null)).andExpect(status().isBadRequest());
    callPut("/admin/v1/users/" + UUID.randomUUID(), profile("A", "B", "a@example.org", null)).andExpect(status().isNotFound());
    verifyNoInteractions(keycloak);
  }

  // ---- PATCH status ---------------------------------------------------------------------------

  @Test
  void deactivatingDisablesKeycloakEndsSessionsKeepsTheRolesAndAudits() throws Exception {
    give(target, roles.get(0), roles.get(1));

    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("INACTIVE"))
        .andExpect(jsonPath("$.data.roles.length()").value(2));

    assertThat(reload(target).getStatus()).isEqualTo(UserStatus.INACTIVE);
    assertThat(userRoleRepository.findAllByUserId(target.getId())).hasSize(2); // kept
    verify(keycloak).setEnabled(KC_ID, false);
    verify(keycloak).logoutUser(KC_ID);
    assertThat(auditActions()).containsExactly("USER_DEACTIVATED");
  }

  @Test
  void reactivatingRestoresAccessWithoutRepickingRoles() throws Exception {
    give(target, roles.get(0), roles.get(1), roles.get(2));
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}").andExpect(status().isOk());
    int rolesBefore = userRoleRepository.findAllByUserId(target.getId()).size();

    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"ACTIVE\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.roles.length()").value(3));

    verify(keycloak).setEnabled(KC_ID, true);
    assertThat(userRoleRepository.findAllByUserId(target.getId())).hasSize(rolesBefore);
    assertThat(auditActions()).containsExactly("USER_DEACTIVATED", "USER_REACTIVATED");
  }

  @Test
  void asNoOpStatusChangeCallsNothingAndWritesNoAuditRow() throws Exception {
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());

    verifyNoInteractions(keycloak);
    assertThat(audit()).isEmpty();
  }

  @Test
  void youCannotDeactivateYourself() throws Exception {
    callPatch("/admin/v1/users/" + actor.getId() + "/status", "{\"status\":\"INACTIVE\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CANNOT_DEACTIVATE_SELF"));

    assertThat(reload(actor).getStatus()).isEqualTo(UserStatus.ACTIVE);
    verifyNoInteractions(keycloak);
  }

  @Test
  void keycloakFailingToDisableLeavesTheUserActiveAndWritesNoAudit() throws Exception {
    doThrow(new SyncFailedException("down")).when(keycloak).setEnabled(any(), org.mockito.ArgumentMatchers.anyBoolean());

    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}").andExpect(status().isBadGateway());

    assertThat(reload(target).getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(audit()).isEmpty();
  }

  @Test
  void ifEndingSessionsFailsTheDisableIsReversedAndEverythingRollsBack() throws Exception {
    doThrow(new SyncFailedException("logout failed")).when(keycloak).logoutUser(any());

    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}").andExpect(status().isBadGateway());

    verify(keycloak).setEnabled(KC_ID, false); // applied...
    verify(keycloak).setEnabled(KC_ID, true); // ...then reversed
    assertThat(reload(target).getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(audit()).isEmpty(); // clean reversal: nothing to reconcile
  }

  @Test
  void anInvalidStatusIs400() throws Exception {
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"LOCKED\"}").andExpect(status().isBadRequest());
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{}").andExpect(status().isBadRequest());
  }

  // ---- PUT roles ------------------------------------------------------------------------------

  @Test
  void aSixthRoleIsExactlyOneAssignCallAndNoRemoves() throws Exception {
    give(target, roles.get(0), roles.get(1), roles.get(2), roles.get(3), roles.get(4));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(0), roles.get(1), roles.get(2), roles.get(3), roles.get(4), roles.get(5)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roles.length()").value(6));

    verify(keycloak, times(1)).assignRealmRoles(KC_ID, List.of(roles.get(5).getName())); // only the delta
    verify(keycloak, never()).removeRealmRoles(any(), any());
    assertThat(roleNamesOf(target)).hasSize(6);
    assertThat(auditActions()).containsExactly("USER_ROLES_UPDATED");
    assertThat(audit().get(0).getBefore()).doesNotContain(roles.get(5).getName());
    assertThat(audit().get(0).getAfter()).contains(roles.get(5).getName());
  }

  @Test
  void aSwapAddsTheNewRoleAndRemovesTheOldOneAndNothingElse() throws Exception {
    give(target, roles.get(0), roles.get(1));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(1), roles.get(2))).andExpect(status().isOk());

    verify(keycloak).assignRealmRoles(KC_ID, List.of(roles.get(2).getName()));
    verify(keycloak).removeRealmRoles(KC_ID, List.of(roles.get(0).getName()));
    assertThat(roleNamesOf(target)).containsExactlyInAnyOrder(roles.get(1).getName(), roles.get(2).getName());
  }

  @Test
  void anEmptySetClearsAllRolesAndRemovesThemInKeycloak() throws Exception {
    give(target, roles.get(0), roles.get(1));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds()).andExpect(status().isOk()).andExpect(jsonPath("$.data.roles.length()").value(0));

    verify(keycloak, never()).assignRealmRoles(any(), any());
    verify(keycloak).removeRealmRoles(eq(KC_ID), anyList());
    assertThat(userRoleRepository.findAllByUserId(target.getId())).isEmpty();
  }

  @Test
  void theSameSetAgainTouchesNothingAndWritesNoAuditRow() throws Exception {
    give(target, roles.get(0), roles.get(1));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(1), roles.get(0))).andExpect(status().isOk());

    verifyNoInteractions(keycloak);
    assertThat(audit()).isEmpty();
  }

  @Test
  void addingARoleKeycloakDoesNotKnowYetIs409AndChangesNothing() throws Exception {
    give(target, roles.get(0));
    when(keycloak.missingRealmRoles(anyCollection())).thenReturn(List.of(roles.get(1).getName()));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(0), roles.get(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ROLE_NOT_SYNCED"));

    assertThat(roleNamesOf(target)).containsExactly(roles.get(0).getName());
    verify(keycloak, never()).assignRealmRoles(any(), any());
    assertThat(audit()).isEmpty();
  }

  @Test
  void anUnknownRoleIdIs400() throws Exception {
    callPut("/admin/v1/users/" + target.getId() + "/roles", "{\"roleIds\":[\"" + UUID.randomUUID() + "\"]}").andExpect(status().isBadRequest());
    verifyNoInteractions(keycloak);
  }

  @Test
  void ifRemovingFailsAfterAssigningTheAssignIsReversedAndTheDatabaseIsUntouched() throws Exception {
    give(target, roles.get(0));
    // only the REMOVAL of the old role fails; reversing the assign (a remove of the NEW role) works
    doThrow(new SyncFailedException("remove failed")).when(keycloak).removeRealmRoles(any(), eq(List.of(roles.get(0).getName())));

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(1))).andExpect(status().isBadGateway());

    verify(keycloak).assignRealmRoles(KC_ID, List.of(roles.get(1).getName()));
    verify(keycloak).removeRealmRoles(KC_ID, List.of(roles.get(1).getName())); // the reversal
    assertThat(roleNamesOf(target)).containsExactly(roles.get(0).getName()); // local rolled back
    assertThat(audit()).isEmpty(); // clean reversal: nothing to reconcile
  }

  @Test
  void ifKeycloakCannotBeReversedEitherAReconciliationRowIsWritten() throws Exception {
    give(target, roles.get(0));
    doThrow(new SyncFailedException("remove failed")).when(keycloak).removeRealmRoles(any(), anyList()); // breaks the removal AND the reversal

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(1))).andExpect(status().isBadGateway());

    assertThat(roleNamesOf(target)).containsExactly(roles.get(0).getName());
    assertThat(auditActions()).containsExactly("USER_SYNC_FAILED"); // survives the rollback
    assertThat(audit().get(0).getEntityId()).isEqualTo(target.getId().toString());
    assertThat(audit().get(0).getAfter()).contains("role change").contains(target.getUsername());
  }

  @Test
  void aCommitFailingAfterKeycloakChangedReversesKeycloakAndRollsBackLocally() throws Exception {
    give(target, roles.get(0));
    doAnswer(
            inv -> {
              transactionManager.rollback((TransactionStatus) inv.getArgument(0));
              throw new TransactionSystemException("simulated commit failure");
            })
        .when(managementService)
        .commitTransaction(any());

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(0), roles.get(1))).andExpect(status().isBadGateway());

    verify(keycloak).assignRealmRoles(KC_ID, List.of(roles.get(1).getName()));
    verify(keycloak).removeRealmRoles(KC_ID, List.of(roles.get(1).getName())); // reversed
    assertThat(roleNamesOf(target)).containsExactly(roles.get(0).getName());
    assertThat(audit()).isEmpty();
  }

  @Test
  void bothDeactivatingAndRemovingTheRoleConsultTheLastSuperAdminGuard() throws Exception {
    // The guard's own logic is unit-tested (AdminLockoutGuardTest). Here we only prove it is wired into
    // both endpoints — without touching the real SUPER_ADMIN accounts in the shared database.
    Role superAdmin = roleRepository.findByName("SUPER_ADMIN").orElseThrow();
    give(target, superAdmin);
    doThrow(org.openphc.cce.admin.exception.ConflictException.lastSuperAdmin()).when(lockoutGuard).assertNotLastSuperAdmin(target.getId());

    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("LAST_SUPER_ADMIN"));
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("LAST_SUPER_ADMIN"));

    assertThat(roleNamesOf(target)).contains("SUPER_ADMIN"); // untouched
    assertThat(reload(target).getStatus()).isEqualTo(UserStatus.ACTIVE);
    verifyNoInteractions(keycloak);
    assertThat(audit()).isEmpty();
  }

  // ---- system accounts ------------------------------------------------------------------------

  private User makeTargetSystem() {
    target.setSystem(true);
    return userRepository.save(target);
  }

  @Test
  void aSystemAccountCannotBeEditedDeactivatedOrHaveItsRolesChanged() throws Exception {
    makeTargetSystem();
    give(target, roles.get(0));

    callPut("/admin/v1/users/" + target.getId(), profile("Hacked", "Name", target.getEmail(), "Elsewhere"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_USER_IMMUTABLE"));
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_USER_IMMUTABLE"));
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"ACTIVE\"}") // even a no-op change is refused
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_USER_IMMUTABLE"));
    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds(roles.get(0), roles.get(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_USER_IMMUTABLE"));
    callPut("/admin/v1/users/" + target.getId() + "/roles", roleIds())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SYSTEM_USER_IMMUTABLE"));

    verifyNoInteractions(keycloak); // refused before any Keycloak call
    User saved = reload(target);
    assertThat(saved.getFirstName()).isEqualTo("First");
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.getFacilityScope()).isEqualTo("Kigali");
    assertThat(roleNamesOf(target)).containsExactly(roles.get(0).getName());
    assertThat(audit()).isEmpty();
  }

  @Test
  void theSystemRuleAppliesToWhoeverIsActing() throws Exception {
    // the actor here is an ordinary active user; the guard is about the TARGET, not the actor's own role
    makeTargetSystem();
    callPatch("/admin/v1/users/" + target.getId() + "/status", "{\"status\":\"INACTIVE\"}").andExpect(status().isConflict());
    assertThat(reload(target).getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void aSystemAccountCanStillBeSentAPasswordResetBecauseThatChangesNothingAboutIt() throws Exception {
    makeTargetSystem();

    callPost("/admin/v1/users/" + target.getId() + "/reset-password").andExpect(status().isOk());

    verify(keycloak).sendActionsEmail(KC_ID, List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"), 259200L);
  }

  @Test
  void fetchingShowsWhetherAnAccountIsASystemAccount() throws Exception {
    mockMvc.perform(get("/admin/v1/users/" + target.getId()).with(token())).andExpect(jsonPath("$.data.isSystem").value(false));
    makeTargetSystem();
    mockMvc.perform(get("/admin/v1/users/" + target.getId()).with(token())).andExpect(jsonPath("$.data.isSystem").value(true));
  }

  // ---- POST reset-password --------------------------------------------------------------------

  @Test
  void resetSendsTheSameTwoActionEmailAs72hAndAudits() throws Exception {
    callPost("/admin/v1/users/" + target.getId() + "/reset-password").andExpect(status().isOk()).andExpect(jsonPath("$.data.sent").value(true));

    verify(keycloak, times(1)).sendActionsEmail(KC_ID, List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"), 259200L);
    assertThat(auditActions()).containsExactly("USER_PASSWORD_RESET");
    assertThat(audit().get(0).getAfter()).doesNotContain("password"); // no secret anywhere, just the email
  }

  @Test
  void resetIsRefusedForAnInactiveUser() throws Exception {
    User inactive = newUser("inactive", UserStatus.INACTIVE);
    extraUsers.add(inactive.getId());

    callPost("/admin/v1/users/" + inactive.getId() + "/reset-password")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("USER_NOT_ACTIVE"));
    verify(keycloak, never()).sendActionsEmail(any(), any(), anyLong());
    assertThat(audit()).isEmpty();
  }

  @Test
  void anEmailFailureOnResetIsA502BecauseTheEmailIsTheWholeOperation() throws Exception {
    doThrow(new SyncFailedException("smtp down")).when(keycloak).sendActionsEmail(any(), any(), anyLong());

    callPost("/admin/v1/users/" + target.getId() + "/reset-password")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));
    assertThat(audit()).isEmpty(); // nothing happened, so nothing audited
  }

  @Test
  void resetOfAnUnknownUserIs404() throws Exception {
    callPost("/admin/v1/users/" + UUID.randomUUID() + "/reset-password").andExpect(status().isNotFound());
    verifyNoInteractions(keycloak);
  }

  @Test
  void everyNewEndpointRequiresAToken() throws Exception {
    UUID id = UUID.randomUUID();
    mockMvc.perform(get("/admin/v1/users/" + id)).andExpect(status().isUnauthorized());
    mockMvc.perform(put("/admin/v1/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    mockMvc.perform(patch("/admin/v1/users/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    mockMvc.perform(put("/admin/v1/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/admin/v1/users/" + id + "/reset-password")).andExpect(status().isUnauthorized());
  }
}
