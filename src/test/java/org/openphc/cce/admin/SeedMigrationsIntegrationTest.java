package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** What V4 and V5 left in the real local ccedb (Flyway applies them at context start). */
@SpringBootTest
class SeedMigrationsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void v5GivesSuperAdminExactlyGetAndPostOnProtocolDefinitions() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select http_method, uri_pattern, resource_category from api_permissions"
                + " where permission_name = 'SUPER_ADMIN' and uri_pattern like '/v1/protocol/%' order by http_method");

    assertThat(rows).hasSize(2);
    assertThat(rows)
        .extracting(r -> r.get("http_method") + " " + r.get("uri_pattern"))
        .containsExactly("GET /v1/protocol/protocol-definitions/**", "POST /v1/protocol/protocol-definitions/**");
    assertThat(rows).allSatisfy(r -> assertThat(r.get("resource_category")).isEqualTo("Protocol"));
  }

  @Test
  void v4LeftTheSevenInsightsMenusAndFourAdminTabsAllLinkedToSuperAdmin() {
    // The seeded entries must still be there under the right console. Not "exactly seven": admins can add their own
    // insights menus from the Menus screen, and may arrange any of them, and this runs against the shared local ccedb.
    assertThat(jdbc.queryForList("select menu_key from menus where console = 'INSIGHTS'", String.class))
        .contains("dashboard", "facilities", "compliance", "deviations", "patients", "events", "ingestion");
    // The admin tabs only ever come from a migration, so that set IS exact (in any order: they can be arranged too).
    assertThat(jdbc.queryForList("select menu_key from menus where console = 'ADMIN'", String.class))
        .containsExactlyInAnyOrder("admin-users", "admin-roles", "admin-menus", "admin-protocols");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from role_menus rm join roles r on r.id = rm.role_id join menus m on m.id = rm.menu_id"
                    + " where r.name = 'SUPER_ADMIN' and m.console = 'ADMIN'",
                Integer.class))
        .isEqualTo(4);
  }
}
