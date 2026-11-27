package org.openphc.cce.admin.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.exception.ConflictException;
import org.openphc.cce.admin.exception.NotFoundException;
import org.openphc.cce.admin.exception.ValidationException;
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
    // No explicit position: the end of the list, so a new entry never jumps in front of the arrangement.
    menu.setSortOrder(
        request.sortOrder() != null ? request.sortOrder() : menuRepository.maxSortOrder(Menu.CONSOLE_INSIGHTS) + 1);
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
   * Arranges ONE console's entries. {@code requested} must be exactly that console's current set of ids: if another
   * admin added or deleted an entry while this one was dragging, the call is refused ({@code MENU_ORDER_OUTDATED}) and
   * nothing changes, so an entry is never silently dropped or put at a stale position. The positions become 1..n in the
   * order given, in one transaction. The admin console's own tabs can be arranged too (it only changes the order of its
   * sidebar); the protection rules are about delete, deactivate and path, not order.
   */
  @Transactional
  public List<MenuResponse> reorder(String console, List<UUID> requested, UUID actorId, String ip) {
    if (new HashSet<>(requested).size() != requested.size()) {
      throw new ValidationException("menuIds must not contain the same id twice");
    }
    Comparator<Menu> byPosition = Comparator.comparingInt(Menu::getSortOrder).thenComparing(Menu::getLabel);
    List<Menu> current = menuRepository.findAllByConsoleOrderBySortOrderAsc(console).stream().sorted(byPosition).toList();
    if (!current.stream().map(Menu::getId).collect(Collectors.toSet()).equals(new HashSet<>(requested))) {
      throw ConflictException.menuOrderOutdated(console);
    }

    Map<UUID, Menu> byId = current.stream().collect(Collectors.toMap(Menu::getId, m -> m));
    for (int i = 0; i < requested.size(); i++) {
      byId.get(requested.get(i)).setSortOrder(i + 1);
    }
    menuRepository.saveAll(current);
    menuRepository.flush();

    Map<String, Object> before = new LinkedHashMap<>();
    before.put("console", console);
    before.put("menuIds", current.stream().map(Menu::getId).toList());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("console", console);
    after.put("menuIds", requested);
    auditLogWriter.write(actorId, AuditAction.MENU_ORDER_UPDATED, ENTITY_TYPE, console, before, after, ip);
    return list();
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
