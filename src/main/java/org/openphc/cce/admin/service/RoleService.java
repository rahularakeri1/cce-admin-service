package org.openphc.cce.admin.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.dto.CreateRoleRequest;
import org.openphc.cce.admin.dto.RoleMenusResponse;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.dto.UpdateRoleRequest;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.NotFoundException;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles CRUD (B-40) and role→menu assignment (B-41). Every mutation writes exactly one audit row
 * in the same transaction — including a menu save that changes nothing.
 */
@Service
public class RoleService {

  private static final String ENTITY_TYPE = "ROLE";
  private static final String SUPER_ADMIN = "SUPER_ADMIN";

  /** The four admin console tabs seeded by V4; SUPER_ADMIN must hold all of them. */
  static final Set<String> ADMIN_TABS = Set.of("admin-users", "admin-roles", "admin-menus", "admin-protocols");

  /** The tabs only SUPER_ADMIN may hold. Protocols is deliberately assignable to other roles. */
  static final Set<String> SUPER_ADMIN_ONLY_TABS = Set.of("admin-users", "admin-roles", "admin-menus");

  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final RoleMenuRepository roleMenuRepository;
  private final MenuRepository menuRepository;
  private final ApiPermissionRepository permissionRepository;
  private final AuditLogWriter auditLogWriter;

  public RoleService(
      RoleRepository roleRepository,
      UserRoleRepository userRoleRepository,
      RoleMenuRepository roleMenuRepository,
      MenuRepository menuRepository,
      ApiPermissionRepository permissionRepository,
      AuditLogWriter auditLogWriter) {
    this.roleRepository = roleRepository;
    this.userRoleRepository = userRoleRepository;
    this.roleMenuRepository = roleMenuRepository;
    this.menuRepository = menuRepository;
    this.permissionRepository = permissionRepository;
    this.auditLogWriter = auditLogWriter;
  }

  @Transactional(readOnly = true)
  public List<RoleResponse> list() {
    return roleRepository.findAll(Sort.by("name")).stream().map(RoleResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public RoleResponse get(UUID id) {
    return RoleResponse.from(find(id));
  }

  @Transactional
  public RoleResponse create(CreateRoleRequest request, UUID actorId, String ip) {
    if (roleRepository.existsByName(request.name())) {
      throw ConflictException.duplicateRoleName(request.name());
    }
    // A role's name is also its api_permissions.permission_name. If gateway already owns rows under
    // that name, a new role would silently adopt them — and deleting it would wipe gateway's rules.
    if (permissionRepository.existsByPermissionName(request.name())) {
      throw ConflictException.roleNameReserved(request.name());
    }
    Role role = new Role();
    role.setId(UUID.randomUUID());
    role.setName(request.name());
    role.setDisplayName(request.displayName().trim());
    role.setDescription(blankToNull(request.description()));
    role.setSystem(false);
    Role saved;
    try {
      saved = roleRepository.saveAndFlush(role);
    } catch (DataIntegrityViolationException race) {
      // Two admins creating the same name at once: the unique constraint is the final authority.
      throw ConflictException.duplicateRoleName(request.name());
    }
    RoleResponse response = RoleResponse.from(saved);
    auditLogWriter.write(actorId, AuditAction.ROLE_CREATED, ENTITY_TYPE, saved.getId().toString(), null, response, ip);
    return response;
  }

  @Transactional
  public RoleResponse update(UUID id, UpdateRoleRequest request, UUID actorId, String ip) {
    Role role = find(id);
    if (role.isSystem()) {
      throw ConflictException.systemRoleImmutable(role.getName());
    }
    RoleResponse before = RoleResponse.from(role);
    role.setDisplayName(request.displayName().trim());
    role.setDescription(blankToNull(request.description()));
    Role saved = roleRepository.saveAndFlush(role);
    RoleResponse after = RoleResponse.from(saved);
    auditLogWriter.write(actorId, AuditAction.ROLE_UPDATED, ENTITY_TYPE, id.toString(), before, after, ip);
    return after;
  }

  /**
   * Both guards are service-layer checks so the client gets a precise code, not an FK error. Also
   * removes the role's api_permissions rows (same transaction) so no orphaned rules keep granting
   * access under a name that no longer exists.
   *
   * @return true if permission rows were removed — the caller then runs a gateway sync so Keycloak
   *     drops the realm role too
   */
  @Transactional
  public boolean delete(UUID id, UUID actorId, String ip) {
    Role role = find(id);
    if (role.isSystem()) {
      throw ConflictException.systemRoleImmutable(role.getName());
    }
    if (userRoleRepository.existsByRoleId(id)) {
      throw ConflictException.roleInUse(role.getName());
    }
    RoleResponse before = RoleResponse.from(role);
    boolean hadPermissionRows = permissionRepository.existsByPermissionName(role.getName());
    if (hadPermissionRows) {
      permissionRepository.deleteAllByPermissionName(role.getName());
    }
    roleRepository.delete(role); // role_menus rows go with it (ON DELETE CASCADE)
    roleRepository.flush();
    auditLogWriter.write(actorId, AuditAction.ROLE_DELETED, ENTITY_TYPE, id.toString(), before, null, ip);
    return hadPermissionRows;
  }

  @Transactional(readOnly = true)
  public RoleMenusResponse menus(UUID roleId) {
    find(roleId);
    return new RoleMenusResponse(currentMenuIds(roleId));
  }

  /**
   * Replaces the role's menu set with exactly {@code requested}. Applied as a diff (delete the
   * removed, insert the added) rather than delete-all-then-insert: Hibernate flushes inserts before
   * deletes, which would collide on the composite key for menus that stay assigned.
   */
  @Transactional
  public RoleMenusResponse replaceMenus(UUID roleId, Collection<UUID> requested, UUID actorId, String ip) {
    Role role = find(roleId);
    Set<UUID> wanted = new LinkedHashSet<>(requested);
    Map<UUID, Menu> known =
        menuRepository.findAllById(wanted).stream().collect(Collectors.toMap(Menu::getId, m -> m));
    for (UUID menuId : wanted) {
      if (!known.containsKey(menuId)) {
        throw NotFoundException.menu(menuId);
      }
    }

    enforceAdminTabRules(role, wanted, known);

    List<UUID> before = currentMenuIds(roleId);
    Set<UUID> existing = new LinkedHashSet<>(before);

    List<RoleMenu> toRemove =
        roleMenuRepository.findAllByRoleId(roleId).stream().filter(rm -> !wanted.contains(rm.getMenuId())).toList();
    roleMenuRepository.deleteAll(toRemove);
    roleMenuRepository.saveAll(
        wanted.stream().filter(id -> !existing.contains(id)).map(id -> new RoleMenu(roleId, id)).toList());
    roleMenuRepository.flush();

    List<UUID> after = currentMenuIds(roleId);
    Map<String, Object> beforePayload = new LinkedHashMap<>();
    beforePayload.put("menuIds", before);
    Map<String, Object> afterPayload = new LinkedHashMap<>();
    afterPayload.put("menuIds", after);
    auditLogWriter.write(
        actorId, AuditAction.ROLE_MENUS_UPDATED, ENTITY_TYPE, roleId.toString(), beforePayload, afterPayload, ip);
    return new RoleMenusResponse(after);
  }

  /**
   * The admin console's own tabs are menu entries, so the menus screen could lock people out of it:
   * SUPER_ADMIN must keep all four (and, as the role that sees everything, every other menu too), and the three management tabs (Users, Roles, Menus) belong to
   * SUPER_ADMIN alone — the service refuses those endpoints to any other role anyway (docs/api-reference.md).
   */
  private void enforceAdminTabRules(Role role, Set<UUID> wanted, Map<UUID, Menu> requested) {
    if (SUPER_ADMIN.equals(role.getName())) {
      List<String> missing =
          menuRepository.findAllByConsoleOrderBySortOrderAsc(Menu.CONSOLE_ADMIN).stream()
              .filter(m -> ADMIN_TABS.contains(m.getMenuKey()) && !wanted.contains(m.getId()))
              .map(Menu::getLabel)
              .toList();
      if (!missing.isEmpty()) {
        throw ConflictException.adminMenuRequired(missing);
      }
      // SUPER_ADMIN sees everything: the whole catalog, so no page can be hidden from every SUPER_ADMIN by accident.
      List<String> missingOthers =
          menuRepository.findAll().stream().filter(m -> !wanted.contains(m.getId())).map(Menu::getLabel).sorted().toList();
      if (!missingOthers.isEmpty()) {
        throw ConflictException.superAdminMenuRequired(missingOthers);
      }
      return;
    }
    List<String> forbidden =
        requested.values().stream()
            .filter(m -> SUPER_ADMIN_ONLY_TABS.contains(m.getMenuKey()))
            .map(Menu::getLabel)
            .sorted()
            .toList();
    if (!forbidden.isEmpty()) {
      throw ConflictException.adminMenuSuperAdminOnly(forbidden, role.getName());
    }
  }

  private List<UUID> currentMenuIds(UUID roleId) {
    return roleMenuRepository.findAllByRoleId(roleId).stream().map(RoleMenu::getMenuId).sorted().toList();
  }

  private Role find(UUID id) {
    return roleRepository.findById(id).orElseThrow(() -> NotFoundException.role(id));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
