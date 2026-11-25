package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.client.KeycloakChangeSet;
import org.openphc.cce.admin.client.KeycloakGateway;
import org.openphc.cce.admin.client.KeycloakUserProfile;
import org.openphc.cce.admin.config.KeycloakProperties;
import org.openphc.cce.admin.dto.ReplaceUserRolesRequest;
import org.openphc.cce.admin.dto.UpdateUserRequest;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.NotFoundException;
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
 * Everything done to a user after creation (B-33..B-36). Not {@code @Transactional}: each change that
 * also lives in Keycloak is LOCAL-FIRST WITH ROLLBACK, like creation —
 *
 * <pre>
 *   checks that need no write
 *   BEGIN → local change + audit → Keycloak changes (a failure reverses the ones already applied)
 *   → COMMIT (a failure reverses the Keycloak changes)
 * </pre>
 *
 * If a reversal itself fails, Keycloak and the database may disagree: a USER_SYNC_FAILED row is
 * written in its own transaction so it can be reconciled by hand.
 */
@Service
public class UserManagementService {

  private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final AuditLogWriter auditLogWriter;
  private final KeycloakGateway keycloak;
  private final KeycloakProperties keycloakProperties;
  private final AdminLockoutGuard lockoutGuard;
  private final PlatformTransactionManager transactionManager;

  public UserManagementService(
      UserRepository userRepository,
      RoleRepository roleRepository,
      UserRoleRepository userRoleRepository,
      AuditLogWriter auditLogWriter,
      KeycloakGateway keycloak,
      KeycloakProperties keycloakProperties,
      AdminLockoutGuard lockoutGuard,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.userRoleRepository = userRoleRepository;
    this.auditLogWriter = auditLogWriter;
    this.keycloak = keycloak;
    this.keycloakProperties = keycloakProperties;
    this.lockoutGuard = lockoutGuard;
    this.transactionManager = transactionManager;
  }

  public record Result(UserResponse user, List<String> warnings) {}

  // ---------------------------------------------------------------------------------------------
  // PUT /users/:id — profile
  // ---------------------------------------------------------------------------------------------

  public Result updateProfile(UUID id, UpdateUserRequest request, UUID actorId, String ip) {
    User current = find(id);
    String firstName = request.firstName().trim();
    String lastName = request.lastName().trim();
    String email = request.email().trim().toLowerCase();
    String facilityScope = blankToNull(request.facilityScope());
    boolean emailChanged = !email.equals(current.getEmail());
    if (emailChanged && userRepository.existsByEmailAndIdNot(email, id)) {
      throw ConflictException.emailInUse(email);
    }

    Map<String, Object> before = profileSnapshot(current);
    AtomicReference<String> keycloakId = new AtomicReference<>();

    UserResponse updated =
        applyLocalFirst(
            "profile update",
            current,
            actorId,
            ip,
            () -> {
              User user = userRepository.findById(id).orElseThrow(() -> NotFoundException.user(id));
              user.setFirstName(firstName);
              user.setLastName(lastName);
              user.setEmail(email);
              user.setFacilityScope(facilityScope);
              try {
                userRepository.saveAndFlush(user);
              } catch (DataIntegrityViolationException race) {
                throw ConflictException.emailInUse(email); // two admins at once
              }
              Map<String, Object> after = profileSnapshot(user);
              auditLogWriter.write(actorId, AuditAction.USER_UPDATED, "USER", id.toString(), before, after, ip);
              return toResponse(user);
            },
            result -> {
              String kcId = keycloak.findUserId(current.getUsername());
              keycloakId.set(kcId);
              AtomicReference<KeycloakUserProfile> previous = new AtomicReference<>();
              return new KeycloakChangeSet()
                  .add(
                      "update profile",
                      // a changed email must be re-verified; otherwise leave Keycloak's flag alone
                      () -> previous.set(keycloak.updateUser(kcId, new KeycloakUserProfile(firstName, lastName, email, emailChanged ? Boolean.FALSE : null))),
                      () -> keycloak.updateUser(kcId, previous.get()));
            });

    List<String> warnings = new ArrayList<>();
    if (emailChanged) {
      try {
        keycloak.sendActionsEmail(keycloakId.get(), List.of("VERIFY_EMAIL"), keycloakProperties.inviteLifespanSeconds());
      } catch (RuntimeException e) {
        log.warn("Verification email for the new address {} could not be sent: {}", email, e.getMessage());
        warnings.add("The new email address was saved, but the verification email could not be sent — use Reset Password to resend");
      }
    }
    return new Result(updated, warnings);
  }

  // ---------------------------------------------------------------------------------------------
  // PATCH /users/:id/status — deactivate / reactivate
  // ---------------------------------------------------------------------------------------------

  public UserResponse setStatus(UUID id, UserStatus target, UUID actorUserId, String ip) {
    User current = find(id);
    if (current.getStatus() == target) {
      return toResponse(current); // already there: no Keycloak call, no audit row
    }
    boolean deactivating = target == UserStatus.INACTIVE;
    if (deactivating) {
      if (id.equals(actorUserId)) {
        throw ConflictException.cannotDeactivateSelf();
      }
      lockoutGuard.assertNotLastSuperAdmin(id);
    }

    return applyLocalFirst(
        deactivating ? "deactivation" : "reactivation",
        current,
        actorUserId,
        ip,
        () -> {
          User user = userRepository.findById(id).orElseThrow(() -> NotFoundException.user(id));
          user.setStatus(target);
          userRepository.saveAndFlush(user);
          Map<String, Object> from = Map.of("status", current.getStatus().name());
          Map<String, Object> to = Map.of("status", target.name());
          auditLogWriter.write(
              actorUserId, deactivating ? AuditAction.USER_DEACTIVATED : AuditAction.USER_REACTIVATED, "USER", id.toString(), from, to, ip);
          return toResponse(user);
        },
        result -> {
          String kcId = keycloak.findUserId(current.getUsername());
          KeycloakChangeSet changes =
              new KeycloakChangeSet()
                  .add(deactivating ? "disable account" : "enable account", () -> keycloak.setEnabled(kcId, !deactivating), () -> keycloak.setEnabled(kcId, deactivating));
          if (deactivating) {
            // Without this, an already-issued refresh token keeps working until it expires.
            changes.add("end sessions", () -> keycloak.logoutUser(kcId), () -> {});
          }
          return changes;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // PUT /users/:id/roles — full-replacement diff (Flow 3)
  // ---------------------------------------------------------------------------------------------

  public UserResponse replaceRoles(UUID id, ReplaceUserRolesRequest request, UUID actorId, String ip) {
    User current = find(id);
    Set<UUID> requested = new LinkedHashSet<>(request.roleIds());
    Map<UUID, Role> known = roleRepository.findAllById(requested).stream().collect(Collectors.toMap(Role::getId, r -> r));
    for (UUID roleId : requested) {
      if (!known.containsKey(roleId)) {
        throw new ValidationException("Unknown roleId: " + roleId);
      }
    }

    Set<UUID> existing = userRoleRepository.findAllByUserId(id).stream().map(UserRole::getRoleId).collect(Collectors.toCollection(LinkedHashSet::new));
    Set<UUID> addedIds = new LinkedHashSet<>(requested);
    addedIds.removeAll(existing);
    Set<UUID> removedIds = new LinkedHashSet<>(existing);
    removedIds.removeAll(requested);
    if (addedIds.isEmpty() && removedIds.isEmpty()) {
      return toResponse(current); // nothing changed: no Keycloak call, no audit row
    }

    Map<UUID, String> names = new LinkedHashMap<>();
    roleRepository.findAllById(union(existing, requested)).forEach(r -> names.put(r.getId(), r.getName()));
    List<String> added = addedIds.stream().map(names::get).sorted().toList();
    List<String> removed = removedIds.stream().map(names::get).sorted().toList();

    // Read-only Keycloak check before anything is written (same rule as creation).
    List<String> missing = added.isEmpty() ? List.of() : keycloak.missingRealmRoles(added);
    if (!missing.isEmpty()) {
      throw ConflictException.roleNotSynced(missing);
    }
    Role superAdmin = roleRepository.findByName(AdminLockoutGuard.SUPER_ADMIN).orElse(null);
    if (superAdmin != null && removedIds.contains(superAdmin.getId()) && current.getStatus() == UserStatus.ACTIVE) {
      lockoutGuard.assertNotLastSuperAdmin(id);
    }

    List<String> beforeNames = existing.stream().map(names::get).sorted().toList();
    List<String> afterNames = requested.stream().map(names::get).sorted().toList();

    return applyLocalFirst(
        "role change",
        current,
        actorId,
        ip,
        () -> {
          User user = userRepository.findById(id).orElseThrow(() -> NotFoundException.user(id));
          List<UserRole> links = userRoleRepository.findAllByUserId(id);
          userRoleRepository.deleteAll(links.stream().filter(l -> removedIds.contains(l.getRoleId())).toList());
          userRoleRepository.saveAll(addedIds.stream().map(rid -> new UserRole(id, rid)).toList());
          userRoleRepository.flush();
          auditLogWriter.write(
              actorId, AuditAction.USER_ROLES_UPDATED, "USER", id.toString(), Map.of("roles", beforeNames), Map.of("roles", afterNames), ip);
          return toResponse(user);
        },
        result -> {
          String kcId = keycloak.findUserId(current.getUsername());
          KeycloakChangeSet changes = new KeycloakChangeSet();
          // ONLY the delta is touched in Keycloak — a sixth role is one assign call, not six.
          if (!added.isEmpty()) {
            changes.add("assign " + added, () -> keycloak.assignRealmRoles(kcId, added), () -> keycloak.removeRealmRoles(kcId, added));
          }
          if (!removed.isEmpty()) {
            changes.add("remove " + removed, () -> keycloak.removeRealmRoles(kcId, removed), () -> keycloak.assignRealmRoles(kcId, removed));
          }
          return changes;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // POST /users/:id/reset-password — resend or genuine reset
  // ---------------------------------------------------------------------------------------------

  public void resetPassword(UUID id, UUID actorId, String ip) {
    User user = find(id);
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw ConflictException.userNotActive(user.getUsername());
    }
    String kcId = keycloak.findUserId(user.getUsername());
    // The email IS the whole operation here, so a failure is a 502 (unlike creation, where it's a warning).
    keycloak.sendActionsEmail(kcId, UserCreationService.INVITE_ACTIONS, keycloakProperties.inviteLifespanSeconds());
    auditLogWriter.write(actorId, AuditAction.USER_PASSWORD_RESET, "USER", id.toString(), null, Map.of("email", user.getEmail()), ip);
  }

  // ---------------------------------------------------------------------------------------------

  /** The local-first transaction shared by every change that also lives in Keycloak. */
  private <T> T applyLocalFirst(
      String operation,
      User subject,
      UUID actorId,
      String ip,
      Supplier<T> localWork,
      Function<T, KeycloakChangeSet> keycloakChanges) {
    TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
    T result;
    try {
      result = localWork.get();
    } catch (RuntimeException e) {
      transactionManager.rollback(tx);
      throw e;
    }

    KeycloakChangeSet changes;
    try {
      changes = keycloakChanges.apply(result); // may look the user up in Keycloak
    } catch (RuntimeException e) {
      transactionManager.rollback(tx);
      throw asSyncFailure(operation, e);
    }
    try {
      changes.execute(); // on failure it has already reversed what it applied
    } catch (RuntimeException e) {
      if (changes.hasUnreversedChanges()) {
        recordDrift(operation, subject, actorId, e, ip);
      }
      transactionManager.rollback(tx);
      throw asSyncFailure(operation, e);
    }

    try {
      commitTransaction(tx);
    } catch (RuntimeException e) {
      boolean clean = changes.undoApplied();
      if (!clean || changes.hasUnreversedChanges()) {
        recordDrift(operation, subject, actorId, e, ip);
      }
      throw asSyncFailure(operation, e);
    }
    return result;
  }

  /** Seam for the commit guard: package-visible so tests can make the commit itself fail. */
  void commitTransaction(TransactionStatus status) {
    transactionManager.commit(status);
  }

  private void recordDrift(String operation, User subject, UUID actorId, Exception cause, String ip) {
    try {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("operation", operation);
      after.put("username", subject.getUsername());
      after.put("reason", cause.getMessage());
      after.put("note", "Keycloak may differ from the database for this user — reconcile by hand");
      auditLogWriter.writeInNewTransaction(actorId, AuditAction.USER_SYNC_FAILED, "USER", subject.getId().toString(), null, after, ip);
    } catch (RuntimeException auditFailure) {
      log.error("Could not write USER_SYNC_FAILED for {}", subject.getUsername(), auditFailure);
    }
  }

  private static SyncFailedException asSyncFailure(String operation, RuntimeException e) {
    return e instanceof SyncFailedException s ? s : new SyncFailedException("Keycloak " + operation + " failed: " + e.getMessage(), e);
  }

  private UserResponse toResponse(User user) {
    List<UUID> roleIds = userRoleRepository.findAllByUserId(user.getId()).stream().map(UserRole::getRoleId).toList();
    List<String> names = roleRepository.findAllById(roleIds).stream().map(Role::getName).sorted().toList();
    return UserResponse.from(user, names);
  }

  private User find(UUID id) {
    return userRepository.findById(id).orElseThrow(() -> NotFoundException.user(id));
  }

  private Map<String, Object> profileSnapshot(User u) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("firstName", u.getFirstName());
    m.put("lastName", u.getLastName());
    m.put("email", u.getEmail());
    m.put("facilityScope", u.getFacilityScope());
    return m;
  }

  private static Set<UUID> union(Set<UUID> a, Set<UUID> b) {
    Set<UUID> all = new LinkedHashSet<>(a);
    all.addAll(b);
    return all;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
