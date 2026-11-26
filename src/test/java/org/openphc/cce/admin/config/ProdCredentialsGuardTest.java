package org.openphc.cce.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The prod profile must not start on the admin/admin password fallback (Milestone 7). Only the guard and
 * the properties it reads are loaded, so no database or Keycloak is needed.
 */
class ProdCredentialsGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class, ProdCredentialsGuard.class);

  @Test
  void prodWithoutAClientSecretRefusesToStart() {
    runner
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage(rootMessage(context.getStartupFailure()));
              assertThat(rootMessage(context.getStartupFailure())).contains("KEYCLOAK_ADMIN_CLIENT_SECRET");
            });
  }

  @Test
  void prodWithABlankSecretAlsoRefuses() {
    runner
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
        .withPropertyValues("keycloak.admin-client-secret=   ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void prodWithASecretStarts() {
    runner
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
        .withPropertyValues("keycloak.admin-client-secret=s3cret")
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProdCredentialsGuard.class));
  }

  @Test
  void otherProfilesIgnoreTheGuard() {
    runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProdCredentialsGuard.class));
    runner
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProdCredentialsGuard.class));
  }

  private static String rootMessage(Throwable failure) {
    Throwable t = failure;
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t.getMessage();
  }
}
