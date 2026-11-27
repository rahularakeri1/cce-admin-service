package org.openphc.cce.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.NotFoundException;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The menu catalog itself — not any role's selection from it (that is RoleService.replaceMenus). Every mutation
 * writes exactly one audit row in the same transaction (B-44).
 *
 * <p>The admin console's own tabs ({@link Menu#CONSOLE_ADMIN}) are protected: the console builds its sidebar from
 * them, so deleting or deactivating one, or moving its path, would lock everyone out of a screen with no way back
 * short of editing the database. Their label, icon and sort order stay editable.
 */
@Service
public class MenuService {

  private static final String ENTITY_TYPE = "MENU";

  private final MenuRepository menuRepository;
  private final RoleMenuRepository roleMenuRepository;
  private final AuditLogWriter auditLogWriter;

  public MenuService(MenuRepository menuRepository, RoleMenuRepository roleMenuRepository, AuditLogWriter auditLogWriter) {
    this.menuRepository = menuRepository;
    this.roleMenuRepository = roleMenuRepository;
    this.auditLogWriter = auditLogWriter;
  }

  /** The full catalog, including inactive entries: insights menus first, then the admin tabs. */
  @Transactional(readOnly = true)
  public List<MenuResponse> list() {
    Sort order = Sort.by(Sort.Order.desc("console"), Sort.Order.asc("sortOrder"), Sort.Order.asc("label"));
    return menuRepository.findAll(order).stream().map(MenuResponse::from).toList();
  }

  @Transactional
  public MenuResponse create(MenuRequest request, UUID actorId, String ip) {
    String key = request.menuKey();
    if (menuRepository.existsByMenuKey(key)) {
      throw ConflictException.duplicateMenuKey(key);
    }
    Menu menu = new Menu();
    menu.setId(UUID.randomUUID());
    menu.setMenuKey(key);
    menu.setConsole(Menu.CONSOLE_INSIGHTS); // admin tabs need code behind them: migrations only
    apply(menu, request);
    menu.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
    menu.setActive(request.isActive() == null || request.isActive());

    Menu saved;
    try {
      saved = menuRepository.saveAndFlush(menu);
    } catch (DataIntegrityViolationException race) {
      // Two admins creating the same key at once: the unique constraint is the final authority.
      throw ConflictException.duplicateMenuKey(key);
    }
    MenuResponse response = MenuResponse.from(saved);
    auditLogWriter.write(actorId, AuditAction.MENU_CREATED, ENTITY_TYPE, saved.getId().toString(), null, response, ip);
    return response;
  }

  @Transactional
  public MenuResponse update(UUID id, MenuRequest request, UUID actorId, String ip) {
    Menu menu = find(id);
    if (!menu.getMenuKey().equals(request.menuKey())) {
      throw ConflictException.menuKeyImmutable(menu.getMenuKey());
    }
    boolean admin = isAdminTab(menu);
    if (admin && !menu.getPath().equals(request.path())) {
      throw ConflictException.adminMenuProtected(menu.getLabel(), "its path can't be changed");
    }
    if (admin && Boolean.FALSE.equals(request.isActive())) {
      throw ConflictException.adminMenuProtected(menu.getLabel(), "it can't be deactivated");
    }

    MenuResponse before = MenuResponse.from(menu);
    apply(menu, request);
    if (request.sortOrder() != null) {
      menu.setSortOrder(request.sortOrder());
    }
    if (request.isActive() != null) {
      menu.setActive(request.isActive());
    }
    MenuResponse after = MenuResponse.from(menuRepository.saveAndFlush(menu));
    auditLogWriter.write(actorId, AuditAction.MENU_UPDATED, ENTITY_TYPE, id.toString(), before, after, ip);
    return after;
  }

  /**
   * Hard delete. The entry also leaves every role that held it: exactly its own role_menus rows go, nobody else's
   * (the audit row records how many). Deactivating (update with isActive=false) is the gentler way to retire one.
   */
  @Transactional
  public void delete(UUID id, UUID actorId, String ip) {
    Menu menu = find(id);
    if (isAdminTab(menu)) {
      throw ConflictException.adminMenuProtected(menu.getLabel(), "it can't be deleted");
    }
    long assignments = roleMenuRepository.countByMenuId(id);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("menu", MenuResponse.from(menu));
    before.put("roleAssignmentsRemoved", assignments);

    roleMenuRepository.deleteAllByMenuId(id);
    menuRepository.delete(menu);
    menuRepository.flush();
    auditLogWriter.write(actorId, AuditAction.MENU_DELETED, ENTITY_TYPE, id.toString(), before, null, ip);
  }

  private static boolean isAdminTab(Menu menu) {
    return Menu.CONSOLE_ADMIN.equals(menu.getConsole());
  }

  /** label, path and icon: the fields every update may change (key and console are fixed once created). */
  private static void apply(Menu menu, MenuRequest request) {
    menu.setLabel(request.label().trim());
    menu.setPath(request.path().trim());
    String icon = request.icon();
    menu.setIcon(icon == null || icon.isBlank() ? null : icon.trim());
  }

  private Menu find(UUID id) {
    return menuRepository.findById(id).orElseThrow(() -> NotFoundException.menu(id));
  }
}
