package org.openphc.cce.admin.controller;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated (B-05). Best-effort DB status — a DB outage must not make this throw. */
@RestController
@RequestMapping("/admin/v1")
public class HealthController {

  private final DataSource dataSource;

  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    try (Connection connection = dataSource.getConnection()) {
      boolean ok = connection.isValid(2);
      return Map.of("status", ok ? "UP" : "DEGRADED");
    } catch (Exception e) {
      return Map.of("status", "DEGRADED");
    }
  }
}
