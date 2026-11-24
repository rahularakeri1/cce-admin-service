package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.client.KeycloakGateway;
import org.openphc.cce.admin.client.KeycloakUserExistsException;
import org.openphc.cce.admin.client.KeycloakUserSpec;
import org.openphc.cce.admin.config.KeycloakProperties;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.openphc.cce.admin.exception.ValidationException;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * POST /users — LOCAL-FIRST WITH ROLLBACK (docs/flow-diagrams.md Flow 2). Deliberately NOT
 * {@code @Transactional}: the transaction is driven by hand so the COMMIT itself can be guarded.
 *
 * <pre>
 *   validate + checks (no tx, no writes)
 *   BEGIN → insert users/user_roles + USER_CREATED audit → Keycloak create → Keycloak assign roles → COMMIT
 *   → invitation email (never fails the request)
 * </pre>
 */
@Service
public class UserCreationService {

  private static final Logger log = LoggerFactory.getLogger(UserCreationService.class);
  static final List<String> INVITE_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final AuditLogWriter auditLogWriter;
  private final KeycloakGateway keycloak;
  private final KeycloakProperties keycloakProperties;
  private final PlatformTransactionManager transactionManager;

  public UserCreationService(
      UserRepository userRepository,
      RoleRepository roleRepository,
      UserRoleRepository userRoleRepository,
      AuditLogWriter auditLogWriter,
      KeycloakGateway keycloak,
      KeycloakProperties keycloakProperties,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.userRoleRepository = userRoleRepository;
    this.auditLogWriter = auditLogWriter;
    this.keycloak = keycloak;
    this.keycloakProperties = keycloakProperties;
    this.transactionManager = transactionManager;
  }

  public record Created(UserResponse user, List<String> warnings) {}

  public Created create(CreateUserRequest request, UUID actorId, String ip) {
    // ---- 1. everything that can be rejected WITHOUT touching a transaction or writing anything ----
    String username = request.username().trim().toLowerCase();
    String email = request.email().trim().toLowerCase();
    String firstName = request.firstName().trim();
    String lastName = request.lastName().trim();
    String facilityScope = request.facilityScope() == null || request.facilityScope().isBlank() ? null : request.facilityScope().trim();

    List<UUID> roleIds = new ArrayList<>(new LinkedHashSet<>(request.roleIds()));
    List<Role> roles = roleRepository.findAllById(roleIds);
    if (roles.size() != roleIds.size()) {
      List<UUID> known = roles.stream().map(Role::getId).toList();
      UUID unknown = roleIds.stream().filter(id -> !known.contains(id)).findFirst().orElseThrow();
      throw new ValidationException("Unknown roleId: " + unknown);
    }
    List<String> roleNames = roles.stream().map(Role::getName).sorted().toList();

    // Email first: a duplicate must make ZERO Keycloak calls.
    if (userRepository.existsByEmail(email)) {
      throw ConflictException.emailInUse(email);
    }
    if (userRepository.existsByUsernameIgnoreCase(username)) {
      throw ConflictException.usernameInUse(username);
    }
    // Read-only Keycloak check, still before any transaction: assigning a role Keycloak doesn't know
    // yet would fail mid-way. (A new role only reaches Keycloak once its permissions are synced.)
    List<String> missing = keycloak.missingRealmRoles(roleNames);
    if (!missing.isEmpty()) {
      throw ConflictException.roleNotSynced(missing);
    }

    // ---- 2. local-first transaction, held open across both Keycloak calls ----
    TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
    UUID userId = UUID.randomUUID();
    UUID auditId;
    User saved;
    try {
      User user = new User();
      user.setId(userId);
      user.setUsername(username);
      user.setEmail(email);
      user.setFirstName(firstName);
      user.setLastName(lastName);
      user.setStatus(UserStatus.ACTIVE);
      user.setFacilityScope(facilityScope);
      saved = userRepository.saveAndFlush(user);
      userRoleRepository.saveAll(roles.stream().map(r -> new UserRole(userId, r.getId())).toList());

      Map<String, Object> after = new LinkedHashMap<>();
      after.put("userId", userId.toString());
      after.put("username", username);
      after.put("email", email);
      after.put("roles", roleNames);
      after.put("facilityScope", facilityScope);
      auditId = auditLogWriter.write(actorId, AuditAction.USER_CREATED, "USER", userId.toString(), null, after, ip);
    } catch (DataIntegrityViolationException race) {
      transactionManager.rollback(tx);
      throw ConflictException.emailInUse(email); // two admins creating the same email at once
    } catch (RuntimeException e) {
      transactionManager.rollback(tx);
      throw e;
    }

    String keycloakId;
    try {
      keycloakId = keycloak.createUser(new KeycloakUserSpec(username, email, firstName, lastName));
    } catch (KeycloakUserExistsException e) {
      // Definitive "no": Keycloak created nothing, so a plain rollback is fully clean.
      transactionManager.rollback(tx);
      throw e.getField() == KeycloakUserExistsException.Field.USERNAME
          ? ConflictException.usernameInUse(username)
          : ConflictException.emailInUse(email);
    } catch (RuntimeException e) {
      // Includes an AMBIGUOUS timeout: Keycloak may have acted anyway. Accepted residual risk of
      // local-first (docs/architecture-overview.md §7) — nothing is known about it to reconcile.
      transactionManager.rollback(tx);
      throw e instanceof SyncFailedException ? e : new SyncFailedException("Keycloak user creation failed: " + e.getMessage(), e);
    }

    // From here the Keycloak account is CONFIRMED to exist, so any failure is traceable.
    try {
      keycloak.assignRealmRoles(keycloakId, roleNames);
    } catch (RuntimeException e) {
      recordProvisioningFailure(actorId, keycloakId, username, email, e, ip);
      transactionManager.rollback(tx);
      throw new SyncFailedException("Keycloak role assignment failed: " + e.getMessage(), e);
    }

    try {
      commitTransaction(tx);
    } catch (RuntimeException e) {
      recordProvisioningFailure(actorId, keycloakId, username, email, e, ip);
      throw new SyncFailedException("Saving the user failed after Keycloak accepted it: " + e.getMessage(), e);
    }

    // ---- 3. the invitation email: ONLY after a durable commit, and never fails the request ----
    List<String> warnings = new ArrayList<>();
    try {
      keycloak.sendActionsEmail(keycloakId, INVITE_ACTIONS, keycloakProperties.inviteLifespanSeconds());
    } catch (RuntimeException e) {
      log.warn("Invitation email for {} could not be sent: {}", email, e.getMessage());
      warnings.add("Invitation email could not be sent — use Reset Password to resend");
      try {
        auditLogWriter.markInviteEmailFailed(auditId);
      } catch (RuntimeException auditFailure) {
        log.warn("Could not record inviteEmailSent=false on the audit row", auditFailure);
      }
    }
    return new Created(UserResponse.from(saved, roleNames), warnings);
  }

  /** Seam for the commit guard: package-visible so tests can make the commit itself fail. */
  void commitTransaction(TransactionStatus status) {
    transactionManager.commit(status);
  }

  /**
   * Written in its OWN transaction so it survives the main rollback. entity_id is the Keycloak
   * account's id because no local users row will exist (docs/data-dictionary.md §2.2).
   */
  private void recordProvisioningFailure(UUID actorId, String keycloakId, String username, String email, Exception cause, String ip) {
    try {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("keycloakId", keycloakId);
      after.put("username", username);
      after.put("email", email);
      after.put("reason", cause.getMessage());
      auditLogWriter.writeInNewTransaction(actorId, AuditAction.USER_PROVISIONING_FAILED, "USER", keycloakId, null, after, ip);
    } catch (RuntimeException auditFailure) {
      log.error("Could not write USER_PROVISIONING_FAILED for Keycloak account {}", keycloakId, auditFailure);
    }
  }
}
