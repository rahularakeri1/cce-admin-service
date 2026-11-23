package org.openphc.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;

class MenuUnionServiceTest {

  private final RoleMenuRepository roleMenus = mock(RoleMenuRepository.class);
  private final MenuRepository menus = mock(MenuRepository.class);
  private final MenuUnionService service = new MenuUnionService(roleMenus, menus);

  private final UUID roleA = UUID.randomUUID();
  private final UUID roleB = UUID.randomUUID();

  private Menu menu(String label, int order, boolean active) {
    Menu m = new Menu();
    m.setId(UUID.randomUUID());
    m.setMenuKey(label.toLowerCase());
    m.setLabel(label);
    m.setPath("/" + label.toLowerCase());
    m.setSortOrder(order);
    m.setActive(active);
    return m;
  }

  @Test
  void twoRolesWithOverlappingMenusGetTheUnionWithoutDuplicates() {
    Menu dashboard = menu("Dashboard", 1, true);
    Menu facilities = menu("Facilities", 2, true);
    Menu events = menu("Events", 6, true);
    when(roleMenus.findAllByRoleIdIn(any()))
        .thenReturn(
            List.of(
                new RoleMenu(roleA, dashboard.getId()),
                new RoleMenu(roleA, facilities.getId()),
                new RoleMenu(roleB, facilities.getId()),
                new RoleMenu(roleB, events.getId())));
    when(menus.findAllById(any())).thenReturn(List.of(events, facilities, dashboard));

    List<Menu> result = service.menusForRoles(List.of(roleA, roleB));

    assertThat(result).extracting(Menu::getLabel).containsExactly("Dashboard", "Facilities", "Events");
  }

  @Test
  void inactiveMenusAreExcluded() {
    Menu dashboard = menu("Dashboard", 1, true);
    Menu retired = menu("Retired", 2, false);
    when(roleMenus.findAllByRoleIdIn(any()))
        .thenReturn(
            List.of(new RoleMenu(roleA, dashboard.getId()), new RoleMenu(roleA, retired.getId())));
    when(menus.findAllById(any())).thenReturn(List.of(dashboard, retired));

    assertThat(service.menusForRoles(List.of(roleA))).extracting(Menu::getLabel).containsExactly("Dashboard");
  }

  @Test
  void resultIsOrderedBySortOrderThenLabel() {
    Menu zeta = menu("Zeta", 1, true);
    Menu alpha = menu("Alpha", 1, true);
    Menu first = menu("Zzz", 0, true);
    when(roleMenus.findAllByRoleIdIn(any()))
        .thenReturn(
            List.of(
                new RoleMenu(roleA, zeta.getId()),
                new RoleMenu(roleA, alpha.getId()),
                new RoleMenu(roleA, first.getId())));
    when(menus.findAllById(any())).thenReturn(List.of(zeta, alpha, first));

    assertThat(service.menusForRoles(List.of(roleA)))
        .extracting(Menu::getLabel)
        .containsExactly("Zzz", "Alpha", "Zeta");
  }

  @Test
  void noRolesMeansNoMenusAndNoQueries() {
    assertThat(service.menusForRoles(List.of())).isEmpty();
    verifyNoInteractions(roleMenus, menus);
  }

  @Test
  void rolesWithNoAssignedMenusGiveAnEmptyList() {
    when(roleMenus.findAllByRoleIdIn(any())).thenReturn(List.of());

    assertThat(service.menusForRoles(List.of(roleA))).isEmpty();
    verifyNoInteractions(menus);
  }
}
