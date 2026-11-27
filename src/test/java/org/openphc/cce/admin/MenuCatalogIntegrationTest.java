package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.testsupport.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The menu catalog CRUD against the real local ccedb (Milestone 9). The seeded menus and the four admin tabs are
 * shared state, so every test that touches one puts it back; everything a test creates is removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MenuCatalogIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private MenuRepository menuRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private RoleMenuRepository roleMenuRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private AdminAuditLogRepository auditLogRepository;

  private TestAccounts accounts;
  private User actor;
  private final List<UUID> createdMenuIds = new ArrayList<>();
  private final List<UUID> createdRoleIds = new ArrayList<>();
  private final List<UUID> extraUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    accounts = new TestAccounts(userRepository, roleRepository, userRoleRepository);
    actor = accounts.superAdmin("menu-test-actor");
  }

  @AfterEach
  void cleanUp() {
    extraUserIds.forEach(userRepository::deleteById);
    createdMenuIds.forEach(id -> menuRepository.findById(id).ifPresent(menuRepository::delete)); // role_menus cascade
    createdRoleIds.forEach(id -> roleRepository.findById(id).ifPresent(roleRepository::delete));
    auditLogRepository.deleteAllByActorId(actor.getId());
    userRepository.deleteById(actor.getId());
  }

  private JwtRequestPostProcessor token() {
    return TestAccounts.tokenFor(actor);
  }

  private String uniqueKey() {
    return "test-menu-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static String body(String key, String label, String path, String icon, Integer sort, Boolean active) {
    return "{\"menuKey\":\"" + key + "\",\"label\":\"" + label + "\",\"path\":\"" + path + "\""
        + (icon != null ? ",\"icon\":\"" + icon + "\"" : "")
        + (sort != null ? ",\"sortOrder\":" + sort : "")
        + (active != null ? ",\"isActive\":" + active : "")
        + "}";
  }

  private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String json) throws Exception {
    return mockMvc.perform(req.with(token()).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  /** Creates a menu through the API; registered for cleanup. */
  private UUID createMenu(String key, String label, String path) throws Exception {
    String res =
        send(post("/admin/v1/menus"), body(key, label, path, "StarIcon", 50, null))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(res, "$.data.id"));
    createdMenuIds.add(id);
    return id;
  }

  private Menu adminMenu(String key) {
    return menuRepository.findAllByConsoleOrderBySortOrderAsc(Menu.CONSOLE_ADMIN).stream()
        .filter(m -> m.getMenuKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private Role newRole() {
    Role r = new Role();
    r.setId(UUID.randomUUID());
    r.setName("TEST_MENUCAT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
    r.setDisplayName("Menu catalog test");
    createdRoleIds.add(r.getId());
    return roleRepository.save(r);
  }

  private List<String> auditActions() {
    return auditLogRepository.findAll().stream()
        .filter(a -> actor.getId().equals(a.getActorId()))
        .map(a -> a.getAction())
        .toList();
  }

  // ---- create ---------------------------------------------------------------------------------------

  @Test
  void createdMenuIsAnInsightsEntryActiveByDefaultAndAppearsInTheCatalog() throws Exception {
    String key = uniqueKey();
    send(post("/admin/v1/menus"), body(key, "Reports", "/reports", "ChartBarIcon", 8, null))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.menuKey").value(key))
        .andExpect(jsonPath("$.data.label").value("Reports"))
        .andExpect(jsonPath("$.data.path").value("/reports"))
        .andExpect(jsonPath("$.data.icon").value("ChartBarIcon"))
        .andExpect(jsonPath("$.data.console").value("INSIGHTS"))
        .andExpect(jsonPath("$.data.sortOrder").value(8))
        .andExpect(jsonPath("$.data.isActive").value(true));
    createdMenuIds.addAll(menuRepository.findAll().stream().filter(m -> m.getMenuKey().equals(key)).map(Menu::getId).toList());

    mockMvc.perform(get("/admin/v1/menus").with(token())).andExpect(jsonPath("$.data[*].menuKey").value(hasItem(key)));
    assertThat(auditActions()).contains("MENU_CREATED");
  }

  @Test
  void aNewEntryIsAssignableToARoleStraightAway() throws Exception {
    UUID menuId = createMenu(uniqueKey(), "Assignable", "/assignable");
    Role role = newRole();

    send(put("/admin/v1/roles/" + role.getId() + "/menus"), "{\"menuIds\":[\"" + menuId + "\"]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.menuIds[0]").value(menuId.toString()));
  }

  @Test
  void aConsoleInTheBodyIsIgnored_adminTabsComeFromMigrationsOnly() throws Exception {
    String key = uniqueKey();
    String json = body(key, "Sneaky", "/sneaky", null, null, null).replace("}", ",\"console\":\"ADMIN\"}");
    String res = send(post("/admin/v1/menus"), json).andExpect(status().isCreated()).andExpect(jsonPath("$.data.console").value("INSIGHTS")).andReturn().getResponse().getContentAsString();
    createdMenuIds.add(UUID.fromString(JsonPath.read(res, "$.data.id")));
  }

  @Test
  void aNewEntryWithNoSortOrderGoesToTheEndOfInsights() throws Exception {
    int lastBefore = menuRepository.maxSortOrder(Menu.CONSOLE_INSIGHTS);
    String res = send(post("/admin/v1/menus"), body(uniqueKey(), "Last", "/last", null, null, null)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.sortOrder").value(lastBefore + 1)).andExpect(jsonPath("$.data.icon").doesNotExist()).andReturn().getResponse().getContentAsString();
    createdMenuIds.add(UUID.fromString(JsonPath.read(res, "$.data.id")));
    // and it really is last in the catalog and in a holder's sidebar order
    List<String> insights = JsonPath.read(mockMvc.perform(get("/admin/v1/menus").with(token())).andReturn().getResponse().getContentAsString(), "$.data[?(@.console=='INSIGHTS')].label");
    assertThat(insights.get(insights.size() - 1)).isEqualTo("Last");
  }

  @Test
  void anExplicitSortOrderIsStillHonoured() throws Exception {
    String res = send(post("/admin/v1/menus"), body(uniqueKey(), "Pinned", "/pinned", null, 0, null)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.sortOrder").value(0)).andReturn().getResponse().getContentAsString();
    createdMenuIds.add(UUID.fromString(JsonPath.read(res, "$.data.id")));
  }

  @Test
  void aDuplicateKeyIs409AndNothingIsAdded() throws Exception {
    long before = menuRepository.count();
    send(post("/admin/v1/menus"), body("events", "Another events", "/other", null, null, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_MENU_KEY"));
    assertThat(menuRepository.count()).isEqualTo(before);
  }

  @Test
  void invalidInputIs400() throws Exception {
    for (String bad :
        List.of(
            body("Has Space", "L", "/p", null, null, null),
            body("UPPER", "L", "/p", null, null, null),
            body("", "L", "/p", null, null, null),
            body("ok-key", "", "/p", null, null, null),
            body("ok-key", "L", "no-slash", null, null, null),
            body("ok-key", "L", "/p", null, -1, null))) {
      send(post("/admin/v1/menus"), bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    assertThat(menuRepository.existsByMenuKey("ok-key")).isFalse();
  }

  // ---- update / deactivate ----------------------------------------------------------------------------

  @Test
  void updateChangesLabelPathIconSortAndActive() throws Exception {
    String key = uniqueKey();
    UUID id = createMenu(key, "Before", "/before");

    send(put("/admin/v1/menus/" + id), body(key, "After", "/after", "CogIcon", 99, false))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.label").value("After"))
        .andExpect(jsonPath("$.data.path").value("/after"))
        .andExpect(jsonPath("$.data.icon").value("CogIcon"))
        .andExpect(jsonPath("$.data.sortOrder").value(99))
        .andExpect(jsonPath("$.data.isActive").value(false));
    assertThat(auditActions()).contains("MENU_UPDATED");
  }

  @Test
  void aMissingSortOrderOrActiveMeansLeaveAsIsOnUpdate() throws Exception {
    String key = uniqueKey();
    UUID id = createMenu(key, "Keep", "/keep"); // sort 50, active

    send(put("/admin/v1/menus/" + id), body(key, "Renamed", "/keep", null, null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.label").value("Renamed"))
        .andExpect(jsonPath("$.data.sortOrder").value(50))
        .andExpect(jsonPath("$.data.isActive").value(true));
  }

  @Test
  void theKeyCannotBeChanged() throws Exception {
    String key = uniqueKey();
    UUID id = createMenu(key, "Fixed", "/fixed");

    send(put("/admin/v1/menus/" + id), body(uniqueKey(), "Fixed", "/fixed", null, null, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MENU_KEY_IMMUTABLE"));
    assertThat(menuRepository.findById(id).orElseThrow().getMenuKey()).isEqualTo(key);
  }

  @Test
  void aDeactivatedMenuLeavesTheSidebarButKeepsItsRoleAssignments() throws Exception {
    String key = uniqueKey();
    UUID menuId = createMenu(key, "Hideable", "/hideable");
    Role role = newRole();
    roleMenuRepository.save(new RoleMenu(role.getId(), menuId));
    User holder = accounts.activeUser("menu-test-holder");
    extraUserIds.add(holder.getId());
    userRoleRepository.save(new UserRole(holder.getId(), role.getId()));

    mockMvc.perform(get("/admin/v1/me/menus").with(TestAccounts.tokenFor(holder))).andExpect(jsonPath("$.data[*].path").value(hasItem("/hideable")));

    send(put("/admin/v1/menus/" + menuId), body(key, "Hideable", "/hideable", null, null, false)).andExpect(status().isOk());

    mockMvc.perform(get("/admin/v1/me/menus").with(TestAccounts.tokenFor(holder))).andExpect(jsonPath("$.data[*].path").value(not(hasItem("/hideable"))));
    assertThat(roleMenuRepository.findAllByRoleId(role.getId())).hasSize(1); // the assignment survived

    send(put("/admin/v1/menus/" + menuId), body(key, "Hideable", "/hideable", null, null, true)).andExpect(status().isOk());
    mockMvc.perform(get("/admin/v1/me/menus").with(TestAccounts.tokenFor(holder))).andExpect(jsonPath("$.data[*].path").value(hasItem("/hideable")));
  }

  @Test
  void updateOfAnUnknownIdIs404() throws Exception {
    send(put("/admin/v1/menus/" + UUID.randomUUID()), body("whatever", "L", "/p", null, null, null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("MENU_NOT_FOUND"));
  }

  // ---- delete -----------------------------------------------------------------------------------------

  @Test
  void deleteRemovesTheMenuAndExactlyItsOwnRoleAssignments() throws Exception {
    UUID doomed = createMenu(uniqueKey(), "Doomed", "/doomed");
    UUID survivor = createMenu(uniqueKey(), "Survivor", "/survivor");
    Role roleA = newRole();
    Role roleB = newRole();
    roleMenuRepository.save(new RoleMenu(roleA.getId(), doomed));
    roleMenuRepository.save(new RoleMenu(roleA.getId(), survivor));
    roleMenuRepository.save(new RoleMenu(roleB.getId(), doomed));

    mockMvc.perform(delete("/admin/v1/menus/" + doomed).with(token())).andExpect(status().isNoContent());

    assertThat(menuRepository.existsById(doomed)).isFalse();
    assertThat(roleMenuRepository.findAllByRoleId(roleA.getId())).extracting(RoleMenu::getMenuId).containsExactly(survivor);
    assertThat(roleMenuRepository.findAllByRoleId(roleB.getId())).isEmpty();
    assertThat(roleRepository.existsById(roleA.getId())).isTrue(); // roles themselves are untouched
    assertThat(auditActions()).contains("MENU_DELETED");
    // the audit row says how many assignments went with it
    String deleted = auditLogRepository.findAll().stream().filter(a -> doomed.toString().equals(a.getEntityId()) && "MENU_DELETED".equals(a.getAction())).findFirst().orElseThrow().getBefore();
    assertThat((Integer) JsonPath.read(deleted, "$.roleAssignmentsRemoved")).isEqualTo(2);
    assertThat((String) JsonPath.read(deleted, "$.menu.label")).isEqualTo("Doomed");
  }

  @Test
  void deleteOfAnUnknownIdIs404() throws Exception {
    mockMvc.perform(delete("/admin/v1/menus/" + UUID.randomUUID()).with(token())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MENU_NOT_FOUND"));
  }

  // ---- the four admin tabs are protected ---------------------------------------------------------------

  @Test
  void theAdminTabsCannotBeDeletedOrDeactivated() throws Exception {
    for (String key : List.of("admin-users", "admin-roles", "admin-menus", "admin-protocols")) {
      Menu m = adminMenu(key);
      mockMvc
          .perform(delete("/admin/v1/menus/" + m.getId()).with(token()))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("ADMIN_MENU_PROTECTED"));
      send(put("/admin/v1/menus/" + m.getId()), body(key, m.getLabel(), m.getPath(), m.getIcon(), m.getSortOrder(), false))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("ADMIN_MENU_PROTECTED"));
      assertThat(menuRepository.findById(m.getId())).get().satisfies(after -> assertThat(after.isActive()).isTrue());
    }
  }

  @Test
  void anAdminTabsPathAndKeyAreFixed() throws Exception {
    Menu m = adminMenu("admin-protocols");
    send(put("/admin/v1/menus/" + m.getId()), body(m.getMenuKey(), m.getLabel(), "/somewhere-else", m.getIcon(), m.getSortOrder(), null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ADMIN_MENU_PROTECTED"));
    send(put("/admin/v1/menus/" + m.getId()), body("renamed-key", m.getLabel(), m.getPath(), m.getIcon(), m.getSortOrder(), null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MENU_KEY_IMMUTABLE"));
    assertThat(menuRepository.findById(m.getId()).orElseThrow().getPath()).isEqualTo("/protocols");
  }

  @Test
  void anAdminTabsLabelIconAndSortOrderCanStillBeEdited() throws Exception {
    Menu m = adminMenu("admin-protocols");
    String label = m.getLabel(), icon = m.getIcon();
    int sort = m.getSortOrder();
    try {
      send(put("/admin/v1/menus/" + m.getId()), body(m.getMenuKey(), "Protocol library", m.getPath(), "CogIcon", sort, true))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.label").value("Protocol library"))
          .andExpect(jsonPath("$.data.console").value("ADMIN"));
    } finally {
      send(put("/admin/v1/menus/" + m.getId()), body(m.getMenuKey(), label, m.getPath(), icon, sort, true)).andExpect(status().isOk());
    }
    assertThat(menuRepository.findById(m.getId()).orElseThrow().getLabel()).isEqualTo(label);
  }

  // ---- arranging (PUT /menus/order) --------------------------------------------------------------------

  private List<UUID> orderOf(String console) {
    return menuRepository.findAllByConsoleOrderBySortOrderAsc(console).stream()
        .sorted(java.util.Comparator.comparingInt(Menu::getSortOrder).thenComparing(Menu::getLabel))
        .map(Menu::getId)
        .toList();
  }

  private static String ids(List<UUID> ids) {
    return String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList());
  }

  private ResultActions reorder(String console, List<UUID> menuIds) throws Exception {
    return send(put("/admin/v1/menus/order"), "{\"console\":\"" + console + "\",\"menuIds\":[" + ids(menuIds) + "]}");
  }

  private static List<UUID> reversed(List<UUID> list) {
    List<UUID> copy = new ArrayList<>(list);
    java.util.Collections.reverse(copy);
    return copy;
  }

  @Test
  void aNewOrderIsSavedAsPositionsOneToNAndReadBackInThatOrder() throws Exception {
    UUID extra = createMenu(uniqueKey(), "Extra", "/extra");
    List<UUID> original = orderOf(Menu.CONSOLE_INSIGHTS);
    assertThat(original).contains(extra);
    List<UUID> flipped = reversed(original);
    try {
      reorder(Menu.CONSOLE_INSIGHTS, flipped)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[?(@.console=='INSIGHTS')].id").value(org.hamcrest.Matchers.contains(flipped.stream().map(UUID::toString).toArray())));

      assertThat(orderOf(Menu.CONSOLE_INSIGHTS)).containsExactlyElementsOf(flipped);
      assertThat(menuRepository.findAllByConsoleOrderBySortOrderAsc(Menu.CONSOLE_INSIGHTS)).extracting(Menu::getSortOrder).containsExactly(java.util.stream.IntStream.rangeClosed(1, flipped.size()).boxed().toArray(Integer[]::new));
      // a holder's sidebar follows the new order
      Role role = newRole();
      flipped.forEach(id -> roleMenuRepository.save(new RoleMenu(role.getId(), id)));
      User holder = accounts.activeUser("menu-test-order-holder");
      extraUserIds.add(holder.getId());
      userRoleRepository.save(new UserRole(holder.getId(), role.getId()));
      List<String> paths = JsonPath.read(mockMvc.perform(get("/admin/v1/me/menus").with(TestAccounts.tokenFor(holder))).andReturn().getResponse().getContentAsString(), "$.data[*].path");
      assertThat(paths.get(0)).isEqualTo("/extra"); // "Extra" was last, now first
      assertThat(auditActions()).contains("MENU_ORDER_UPDATED");
    } finally {
      reorder(Menu.CONSOLE_INSIGHTS, original).andExpect(status().isOk()); // the seeded order is shared state
    }
    assertThat(orderOf(Menu.CONSOLE_INSIGHTS)).containsExactlyElementsOf(original);
  }

  @Test
  void theAdminTabsCanBeArrangedToo() throws Exception {
    List<UUID> original = orderOf(Menu.CONSOLE_ADMIN);
    try {
      reorder(Menu.CONSOLE_ADMIN, reversed(original)).andExpect(status().isOk());
      assertThat(orderOf(Menu.CONSOLE_ADMIN)).containsExactlyElementsOf(reversed(original));
      // ...and they stay what they were: still protected, still ADMIN
      assertThat(menuRepository.findAllById(original)).allSatisfy(m -> assertThat(m.getConsole()).isEqualTo("ADMIN"));
    } finally {
      reorder(Menu.CONSOLE_ADMIN, original).andExpect(status().isOk());
    }
    assertThat(orderOf(Menu.CONSOLE_ADMIN)).containsExactlyElementsOf(original);
  }

  @Test
  void arrangingOneConsoleLeavesTheOtherUntouched() throws Exception {
    List<UUID> admin = orderOf(Menu.CONSOLE_ADMIN);
    List<UUID> insights = orderOf(Menu.CONSOLE_INSIGHTS);
    reorder(Menu.CONSOLE_INSIGHTS, insights).andExpect(status().isOk());
    assertThat(orderOf(Menu.CONSOLE_ADMIN)).containsExactlyElementsOf(admin);
  }

  @Test
  void aListThatMissesAnEntryIsRefusedAndNothingChanges() throws Exception {
    List<UUID> original = orderOf(Menu.CONSOLE_INSIGHTS);
    reorder(Menu.CONSOLE_INSIGHTS, reversed(original).subList(1, original.size()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MENU_ORDER_OUTDATED"));
    assertThat(orderOf(Menu.CONSOLE_INSIGHTS)).containsExactlyElementsOf(original);
  }

  @Test
  void aListWithAnUnknownEntryIsRefused() throws Exception {
    List<UUID> withGhost = new ArrayList<>(orderOf(Menu.CONSOLE_INSIGHTS));
    withGhost.add(UUID.randomUUID());
    reorder(Menu.CONSOLE_INSIGHTS, withGhost).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MENU_ORDER_OUTDATED"));
  }

  @Test
  void anEntryOfTheOtherConsoleIsRefused() throws Exception {
    List<UUID> mixed = new ArrayList<>(orderOf(Menu.CONSOLE_INSIGHTS));
    mixed.add(adminMenu("admin-users").getId());
    reorder(Menu.CONSOLE_INSIGHTS, mixed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MENU_ORDER_OUTDATED"));
    // the whole admin list sent for INSIGHTS is refused too
    reorder(Menu.CONSOLE_INSIGHTS, orderOf(Menu.CONSOLE_ADMIN)).andExpect(status().isConflict());
  }

  @Test
  void anEntryAddedMeanwhileMakesAnOldListStale() throws Exception {
    List<UUID> loaded = orderOf(Menu.CONSOLE_INSIGHTS); // what the screen showed
    UUID addedByAnotherAdmin = createMenu(uniqueKey(), "Late arrival", "/late");

    reorder(Menu.CONSOLE_INSIGHTS, reversed(loaded)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MENU_ORDER_OUTDATED"));
    assertThat(orderOf(Menu.CONSOLE_INSIGHTS)).contains(addedByAnotherAdmin); // not dropped
  }

  @Test
  void duplicatesAndBadBodiesAre400() throws Exception {
    List<UUID> original = orderOf(Menu.CONSOLE_INSIGHTS);
    List<UUID> dup = new ArrayList<>(original);
    dup.add(original.get(0));
    reorder(Menu.CONSOLE_INSIGHTS, dup).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    send(put("/admin/v1/menus/order"), "{\"console\":\"SIDEWAYS\",\"menuIds\":[]}").andExpect(status().isBadRequest());
    send(put("/admin/v1/menus/order"), "{\"menuIds\":[]}").andExpect(status().isBadRequest());
    send(put("/admin/v1/menus/order"), "{\"console\":\"INSIGHTS\"}").andExpect(status().isBadRequest());
    assertThat(orderOf(Menu.CONSOLE_INSIGHTS)).containsExactlyElementsOf(original);
  }

  @Test
  void onlyASuperAdminMayArrange() throws Exception {
    User roleless = accounts.activeUser("menu-test-order-roleless");
    extraUserIds.add(roleless.getId());
    mockMvc.perform(put("/admin/v1/menus/order").with(TestAccounts.tokenFor(roleless)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"console\":\"INSIGHTS\",\"menuIds\":[" + ids(orderOf(Menu.CONSOLE_INSIGHTS)) + "]}"))
        .andExpect(status().isForbidden());
  }

  // ---- who may use it ---------------------------------------------------------------------------------

  @Test
  void onlyASuperAdminMayManageTheCatalog() throws Exception {
    User roleless = accounts.activeUser("menu-test-roleless");
    extraUserIds.add(roleless.getId());
    JwtRequestPostProcessor token = TestAccounts.tokenFor(roleless);
    String json = body(uniqueKey(), "Nope", "/nope", null, null, null);

    mockMvc.perform(post("/admin/v1/menus").with(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
    mockMvc.perform(put("/admin/v1/menus/" + adminMenu("admin-users").getId()).with(token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
    mockMvc.perform(delete("/admin/v1/menus/" + UUID.randomUUID()).with(token)).andExpect(status().isForbidden());
    mockMvc.perform(post("/admin/v1/menus").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
  }
}
