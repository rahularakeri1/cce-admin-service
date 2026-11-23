package org.openphc.cce.admin.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.springframework.stereotype.Service;

/**
 * Union of the menus assigned to a set of roles: de-duplicated, active only, ordered by
 * sort_order (B-21). A menu shared by several roles appears once.
 */
@Service
public class MenuUnionService {

  private final RoleMenuRepository roleMenuRepository;
  private final MenuRepository menuRepository;

  public MenuUnionService(RoleMenuRepository roleMenuRepository, MenuRepository menuRepository) {
    this.roleMenuRepository = roleMenuRepository;
    this.menuRepository = menuRepository;
  }

  public List<Menu> menusForRoles(Collection<UUID> roleIds) {
    if (roleIds.isEmpty()) {
      return List.of();
    }
    Set<UUID> menuIds =
        roleMenuRepository.findAllByRoleIdIn(roleIds).stream()
            .map(RoleMenu::getMenuId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (menuIds.isEmpty()) {
      return List.of();
    }
    return menuRepository.findAllById(menuIds).stream()
        .filter(Menu::isActive)
        .sorted(Comparator.comparingInt(Menu::getSortOrder).thenComparing(Menu::getLabel))
        .toList();
  }
}
