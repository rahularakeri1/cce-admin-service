package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.dto.PermissionInput;
import org.openphc.cce.admin.dto.PermissionResponse;
import org.openphc.cce.admin.entity.ApiPermission;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.NotFoundException;
import org.openphc.cce.admin.exception.ValidationException;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A role's permission set lives in gateway-service's api_permissions table, keyed by
 * permission_name = role name. This service only writes rows (never DDL) and never calls gateway:
 * the sync happens AFTER this transaction commits, in a separate bean (GatewaySyncService).
 */
@Service
public class RolePermissionService {

  private static final String ENTITY_TYPE = "ROLE";

  private final RoleRepository roleRepository;
  private final ApiPermissionRepository permissionRepository;
  private final UserRoleRepository userRoleRepository;
  private final AuditLogWriter auditLogWriter;

  public RolePermissionService(
      RoleRepository roleRepository,
      ApiPermissionRepository permissionRepository,
      UserRoleRepository userRoleRepository,
      AuditLogWriter auditLogWriter) {
    this.roleRepository = roleRepository;
    this.permissionRepository = permissionRepository;
    this.userRoleRepository = userRoleRepository;
    this.auditLogWriter = auditLogWriter;
  }

  public record Saved(String roleName, List<PermissionResponse> permissions) {}

  @Transactional(readOnly = true)
  public List<PermissionResponse> get(UUID roleId) {
    return rows(find(roleId).getName()).stream().map(PermissionResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public String roleName(UUID roleId) {
    return find(roleId).getName();
  }

  /**
   * Replaces the role's rows with exactly {@code requested}, as a diff on (method, pattern): kept
   * rows keep their id and get the new description/category, removed rows are deleted, new rows
   * are inserted. One transaction, one audit row — written even when the sync that follows fails.
   */
  @Transactional
  public Saved replace(UUID roleId, List<PermissionInput> requested, UUID actorId, String ip) {
    Role role = find(roleId);
    if (role.isSystem()) {
      throw ConflictException.systemRoleImmutable(role.getName());
    }

    Map<String, PermissionInput> wanted = new LinkedHashMap<>();
    for (PermissionInput input : requested) {
      String key = key(input.httpMethod(), input.uriPattern());
      if (wanted.put(key, input) != null) {
        throw new ValidationException("Duplicate permission: " + input.httpMethod() + " " + input.uriPattern());
      }
    }
    // Gateway's sync deletes any Keycloak role with no rows; don't pull it from under its holders.
    if (wanted.isEmpty() && userRoleRepository.existsByRoleId(roleId)) {
      throw ConflictException.roleNeedsPermissions(role.getName());
    }

    List<ApiPermission> existing = rows(role.getName());
    List<Map<String, String>> before = existing.stream().map(RolePermissionService::snapshot).toList();

    Map<String, ApiPermission> existingByKey = new LinkedHashMap<>();
    existing.forEach(p -> existingByKey.put(key(p.getHttpMethod(), p.getUriPattern()), p));

    List<ApiPermission> toDelete = new ArrayList<>();
    existingByKey.forEach(
        (k, p) -> {
          if (!wanted.containsKey(k)) {
            toDelete.add(p);
          }
        });
    permissionRepository.deleteAll(toDelete);

    List<ApiPermission> toSave = new ArrayList<>();
    wanted.forEach(
        (k, input) -> {
          ApiPermission row = existingByKey.get(k);
          if (row == null) {
            row = new ApiPermission();
            row.setPermissionName(role.getName());
            row.setHttpMethod(input.httpMethod());
            row.setUriPattern(input.uriPattern());
          }
          row.setDescription(blankToNull(input.description()));
          row.setResourceCategory(blankToNull(input.category()));
          toSave.add(row);
        });
    permissionRepository.saveAll(toSave);
    permissionRepository.flush();

    List<ApiPermission> after = rows(role.getName());
    Map<String, Object> beforePayload = new LinkedHashMap<>();
    beforePayload.put("permissions", before);
    Map<String, Object> afterPayload = new LinkedHashMap<>();
    afterPayload.put("permissions", after.stream().map(RolePermissionService::snapshot).toList());
    auditLogWriter.write(
        actorId, AuditAction.ROLE_PERMISSIONS_UPDATED, ENTITY_TYPE, roleId.toString(), beforePayload, afterPayload, ip);

    return new Saved(role.getName(), after.stream().map(PermissionResponse::from).toList());
  }

  private List<ApiPermission> rows(String roleName) {
    List<ApiPermission> rows = new ArrayList<>(permissionRepository.findAllByPermissionName(roleName));
    rows.sort(
        (a, b) -> {
          int byUri = a.getUriPattern().compareTo(b.getUriPattern());
          return byUri != 0 ? byUri : a.getHttpMethod().compareTo(b.getHttpMethod());
        });
    return rows;
  }

  private Role find(UUID id) {
    return roleRepository.findById(id).orElseThrow(() -> NotFoundException.role(id));
  }

  private static String key(String method, String uri) {
    return method + " " + uri;
  }

  private static Map<String, String> snapshot(ApiPermission p) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("httpMethod", p.getHttpMethod());
    m.put("uriPattern", p.getUriPattern());
    m.put("description", p.getDescription());
    m.put("category", p.getResourceCategory());
    return m;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
