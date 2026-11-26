package org.openphc.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.openphc.cce.admin.client.KeycloakGateway;
import org.openphc.cce.admin.client.KeycloakUserExistsException;
import org.openphc.cce.admin.client.KeycloakUserSpec;
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
 * POST /users against the real ccedb with Keycloak replaced by a fake: every branch of Flow 2 —
 * happy path, each failure point, and what must (not) be left behind. In this package so the commit
 * seam of UserCreationService can be made to fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserCreationIntegrationTest {

  private static final List<String> ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private AdminAuditLogRepository auditLogRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockBean private KeycloakGateway keycloak;
  @SpyBean private UserCreationService creationService;

  private User actor;
  private Role role;
  private final List<String> createdEmails = new ArrayList<>();

  @BeforeEach
  void setUp() {
    reset(keycloak);
    when(keycloak.missingRealmRoles(anyCollection())).thenReturn(List.of());
    when(keycloak.createUser(any(KeycloakUserSpec.class))).thenReturn("kc-" + UUID.randomUUID());

    actor = new TestAccounts(userRepository, roleRepository, userRoleRepository).superAdmin("create-actor");

    role = new Role();
    role.setId(UUID.randomUUID());
    role.setName("TEST_CREATE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
    role.setDisplayName("Create test");
    roleRepository.save(role);
  }

  @AfterEach
  void cleanUp() {
    createdEmails.forEach(e -> userRepository.findByEmail(e).ifPresent(u -> userRepository.deleteById(u.getId())));
    auditLogRepository.deleteAllByActorId(actor.getId());
    userRepository.deleteById(actor.getId());
    roleRepository.deleteById(role.getId());
  }

  private JwtRequestPostProcessor token() {
    return TestAccounts.tokenFor(actor);
  }

  private String body(String username, String email, UUID... roleIds) {
    return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"firstName\":\"Fay\",\"lastName\":\"Admin\","
        + "\"facilityScope\":\"Kigali\",\"roleIds\":[" + String.join(",", java.util.Arrays.stream(roleIds).map(i -> "\"" + i + "\"").toList()) + "]}";
  }

  private ResultActions postUser(String json) throws Exception {
    return mockMvc.perform(post("/admin/v1/users").with(token()).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private String freshEmail() {
    String email = "new-user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    createdEmails.add(email);
    return email;
  }

  private List<AdminAuditLog> auditRows() {
    return auditLogRepository.findAll().stream().filter(r -> actor.getId().equals(r.getActorId())).toList();
  }

  // ---- happy path -----------------------------------------------------------------------------

  @Test
  void createsTheUserLocallyThenInKeycloakAssignsRolesAndSendsTheInviteLast() throws Exception {
    String email = freshEmail();
    postUser(body("fay.admin", email, role.getId()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.isSystem").value(false)) // only the bootstrap script makes system accounts
        .andExpect(jsonPath("$.data.roles[0]").value(role.getName()))
        .andExpect(jsonPath("$.data.lastLoginAt").doesNotExist())
        .andExpect(jsonPath("$.warnings").doesNotExist());

    User saved = userRepository.findByEmail(email).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.getFacilityScope()).isEqualTo("Kigali");
    assertThat(saved.getLastLoginAt()).isNull();
    assertThat(userRoleRepository.findAllByUserId(saved.getId())).extracting(UserRole::getRoleId).containsExactly(role.getId());

    assertThat(auditRows()).filteredOn(r -> "USER_CREATED".equals(r.getAction()) && saved.getId().toString().equals(r.getEntityId())).hasSize(1);

    InOrder order = inOrder(keycloak);
    order.verify(keycloak).missingRealmRoles(anyCollection());
    order.verify(keycloak).createUser(new KeycloakUserSpec("fay.admin", email, "Fay", "Admin"));
    order.verify(keycloak).assignRealmRoles(anyString(), eq(List.of(role.getName())));
    order.verify(keycloak).sendActionsEmail(anyString(), eq(ACTIONS), eq(259200L)); // both actions, 72h, exactly once
    verify(keycloak, org.mockito.Mockito.times(1)).sendActionsEmail(anyString(), anyList(), anyLong());
  }

  @Test
  void emailAndUsernameAreStoredLowerCase() throws Exception {
    String email = freshEmail();
    postUser(body("Fay.ADMIN", email.toUpperCase(), role.getId())).andExpect(status().isCreated());

    User saved = userRepository.findByEmail(email).orElseThrow(); // looked up by the lower-case form
    assertThat(saved.getUsername()).isEqualTo("fay.admin");
    verify(keycloak).createUser(new KeycloakUserSpec("fay.admin", email, "Fay", "Admin"));
  }

  @Test
  void aUserCanBeCreatedWithNoRoles() throws Exception {
    String email = freshEmail();
    postUser(body("no.roles", email)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.roles.length()").value(0));
    assertThat(userRoleRepository.findAllByUserId(userRepository.findByEmail(email).orElseThrow().getId())).isEmpty();
  }

  // ---- rejected before anything is touched ----------------------------------------------------

  @Test
  void aDuplicateEmailIs409WithZeroKeycloakCallsEvenWhenOnlyTheCaseDiffers() throws Exception {
    String email = freshEmail();
    postUser(body("first.user", email, role.getId())).andExpect(status().isCreated());
    org.mockito.Mockito.clearInvocations(keycloak);

    postUser(body("second.user", email.toUpperCase(), role.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"));

    verifyNoInteractions(keycloak);
    assertThat(userRepository.existsByUsernameIgnoreCase("second.user")).isFalse();
  }

  @Test
  void aDuplicateUsernameIs409WithZeroKeycloakCalls() throws Exception {
    postUser(body("taken.name", freshEmail(), role.getId())).andExpect(status().isCreated());
    org.mockito.Mockito.clearInvocations(keycloak);

    postUser(body("TAKEN.NAME", freshEmail(), role.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("USERNAME_IN_USE"));
    verifyNoInteractions(keycloak);
  }

  @Test
  void anUnknownRoleIdIs400WithZeroKeycloakCalls() throws Exception {
    postUser(body("ghost.role", freshEmail(), UUID.randomUUID()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    verifyNoInteractions(keycloak);
  }

  @Test
  void invalidBodiesAre400WithZeroKeycloakCalls() throws Exception {
    for (String bad :
        List.of(
            body("ab", freshEmail(), role.getId()), // username too short
            body("has space", freshEmail(), role.getId()),
            body("valid.name", "not-an-email", role.getId()),
            "{\"username\":\"valid.name\",\"email\":\"" + freshEmail() + "\",\"roleIds\":[]}", // names missing
            "{\"username\":\"valid.name\",\"email\":\"" + freshEmail() + "\",\"firstName\":\"A\",\"lastName\":\"B\"}", // roleIds missing
            "{not json")) {
      postUser(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    verifyNoInteractions(keycloak);
    assertThat(auditRows()).isEmpty();
  }

  @Test
  void aRoleNotYetInKeycloakIs409AndNothingIsCreated() throws Exception {
    when(keycloak.missingRealmRoles(anyCollection())).thenReturn(List.of(role.getName()));
    String email = freshEmail();

    postUser(body("unsynced", email, role.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ROLE_NOT_SYNCED"))
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Permissions tab")));

    verify(keycloak, never()).createUser(any());
    assertThat(userRepository.findByEmail(email)).isEmpty();
    assertThat(auditRows()).isEmpty();
  }

  // ---- failure points -------------------------------------------------------------------------

  @Test
  void keycloakCreateFailingIs502WithEverythingRolledBackAndNoAuditRowAtAll() throws Exception {
    when(keycloak.createUser(any())).thenThrow(new SyncFailedException("keycloak down"));
    String email = freshEmail();

    postUser(body("fails.create", email, role.getId()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));

    assertThat(userRepository.findByEmail(email)).isEmpty();
    assertThat(userRepository.existsByUsernameIgnoreCase("fails.create")).isFalse();
    assertThat(auditRows()).isEmpty(); // nothing to reconcile for this failure point
    verify(keycloak, never()).assignRealmRoles(any(), any());
    verify(keycloak, never()).sendActionsEmail(any(), any(), anyLong());
  }

  @Test
  void aDefinitiveDuplicateInKeycloakIs409NotA502AndLeavesNothingBehind() throws Exception {
    when(keycloak.createUser(any())).thenThrow(new KeycloakUserExistsException(KeycloakUserExistsException.Field.EMAIL, "exists"));
    String email = freshEmail();

    postUser(body("kc.has.email", email, role.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"));
    assertThat(userRepository.findByEmail(email)).isEmpty();
    assertThat(auditRows()).isEmpty();

    doThrow(new KeycloakUserExistsException(KeycloakUserExistsException.Field.USERNAME, "exists")).when(keycloak).createUser(any()); // when(...) would re-throw the old stub
    postUser(body("kc.has.username", freshEmail(), role.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("USERNAME_IN_USE"));
    assertThat(auditRows()).isEmpty();
  }

  @Test
  void roleAssignmentFailingAfterTheAccountExistsRecordsAProvisioningFailureAndRollsBack() throws Exception {
    when(keycloak.createUser(any())).thenReturn("kc-account-123");
    doThrow(new SyncFailedException("assign failed")).when(keycloak).assignRealmRoles(any(), any());
    String email = freshEmail();

    postUser(body("fails.assign", email, role.getId()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));

    assertThat(userRepository.findByEmail(email)).isEmpty(); // main transaction rolled back
    List<AdminAuditLog> rows = auditRows();
    assertThat(rows).hasSize(1); // the USER_CREATED row rolled back with it; only the reconciliation row survives
    AdminAuditLog failure = rows.get(0);
    assertThat(failure.getAction()).isEqualTo("USER_PROVISIONING_FAILED");
    assertThat(failure.getEntityType()).isEqualTo("USER");
    assertThat(failure.getEntityId()).isEqualTo("kc-account-123"); // the Keycloak account's own id
    assertThat(failure.getAfter()).contains("kc-account-123").contains(email).contains("assign failed");
    verify(keycloak, never()).sendActionsEmail(any(), any(), anyLong());
  }

  @Test
  void aCommitFailingAfterBothKeycloakCallsAlsoRecordsAProvisioningFailure() throws Exception {
    when(keycloak.createUser(any())).thenReturn("kc-account-456");
    doAnswer(
            inv -> {
              transactionManager.rollback((TransactionStatus) inv.getArgument(0)); // a failed commit leaves it rolled back
              throw new TransactionSystemException("simulated commit failure");
            })
        .when(creationService)
        .commitTransaction(any());
    String email = freshEmail();

    postUser(body("fails.commit", email, role.getId()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"));

    assertThat(userRepository.findByEmail(email)).isEmpty();
    List<AdminAuditLog> rows = auditRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo("USER_PROVISIONING_FAILED");
    assertThat(rows.get(0).getEntityId()).isEqualTo("kc-account-456");
    verify(keycloak).assignRealmRoles(any(), any()); // both Keycloak calls DID succeed first
    verify(keycloak, never()).sendActionsEmail(any(), any(), anyLong()); // nobody gets a link to a rolled-back account
  }

  @Test
  void aFailedInviteEmailStillReturns201WithAWarningAndIsNotedOnTheAuditRow() throws Exception {
    doThrow(new SyncFailedException("smtp down")).when(keycloak).sendActionsEmail(any(), any(), anyLong());
    String email = freshEmail();

    postUser(body("no.mail", email, role.getId()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("Invitation email could not be sent")));

    User saved = userRepository.findByEmail(email).orElseThrow(); // user, roles, row all fully valid
    assertThat(userRoleRepository.findAllByUserId(saved.getId())).hasSize(1);
    AdminAuditLog created =
        auditRows().stream().filter(r -> "USER_CREATED".equals(r.getAction())).findFirst().orElseThrow();
    assertThat(created.getAfter()).contains("\"inviteEmailSent\"").contains("false");
    assertThat(auditRows()).extracting(AdminAuditLog::getAction).doesNotContain("USER_PROVISIONING_FAILED");
  }

  @Test
  void aSuccessfulInviteLeavesNoInviteEmailSentFlagOnTheAuditRow() throws Exception {
    postUser(body("with.mail", freshEmail(), role.getId())).andExpect(status().isCreated());

    AdminAuditLog created =
        auditRows().stream().filter(r -> "USER_CREATED".equals(r.getAction())).findFirst().orElseThrow();
    assertThat(created.getAfter()).doesNotContain("inviteEmailSent");
  }

  @Test
  void theEndpointRequiresAToken() throws Exception {
    mockMvc.perform(post("/admin/v1/users").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
  }
}
