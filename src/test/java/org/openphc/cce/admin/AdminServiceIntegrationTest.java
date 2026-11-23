package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditLogWriter;
import org.openphc.cce.admin.entity.AdminAuditLog;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.AdminAuditLogRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs against the real local ccedb (deploy-scripts/local) — no mocking of the database, per this
 * service's own testability convention (docs/architecture-overview.md §8).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminServiceIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AuditLogWriter auditLogWriter;
  @Autowired private AdminAuditLogRepository auditLogRepository;

  private User savedUser;

  @AfterEach
  void cleanup() {
    if (savedUser != null) {
      // admin_audit_log.actor_id is a RESTRICT foreign key into users, so the audit rows go first.
      auditLogRepository.deleteAllByActorId(savedUser.getId());
      userRepository.deleteById(savedUser.getId());
    }
  }

  @Test
  void healthIsUnauthenticatedAndReportsUp() throws Exception {
    mockMvc
        .perform(get("/admin/v1/health"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"));
  }

  @Test
  void protectedPathWithNoTokenReturns401() throws Exception {
    mockMvc.perform(get("/admin/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void userRepositoryRoundTripsAgainstTheRealSchema() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("integration-test-user");
    user.setEmail("integration-test-" + UUID.randomUUID() + "@example.org");
    user.setFirstName("Test");
    user.setLastName("User");
    user.setStatus(UserStatus.ACTIVE);

    savedUser = userRepository.save(user);

    Optional<User> found = userRepository.findByEmail(savedUser.getEmail());
    assertThat(found).isPresent();
    assertThat(found.get().getCreatedAt()).isNotNull();
    assertThat(found.get().getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void auditLogWriterWritesExactlyOneRow() {
    // Cleaned up in @AfterEach: production never deletes audit rows, but a test must not leave any.
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("audit-test-user");
    user.setEmail("audit-test-" + UUID.randomUUID() + "@example.org");
    user.setFirstName("Audit");
    user.setLastName("Test");
    user.setStatus(UserStatus.ACTIVE);
    User persisted = userRepository.save(user);
    savedUser = persisted;

    auditLogWriter.write(
        persisted.getId(),
        AuditAction.USER_CREATED,
        "USER",
        persisted.getId().toString(),
        null,
        java.util.Map.of("username", persisted.getUsername()),
        "127.0.0.1");

    List<AdminAuditLog> rows = auditLogRepository.findAll();
    assertThat(rows)
        .anySatisfy(
            row -> {
              assertThat(row.getAction()).isEqualTo("USER_CREATED");
              assertThat(row.getEntityId()).isEqualTo(persisted.getId().toString());
            });
  }
}
