package org.openphc.cce.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Under the {@code prod} profile the service refuses to start without {@code KEYCLOAK_ADMIN_CLIENT_SECRET}.
 * Without it the Keycloak admin client falls back to the password grant with the well-known admin/admin
 * defaults, which is a local-development convenience and must never be reachable in a real environment.
 */
@Configuration
@Profile("prod")
public class ProdCredentialsGuard {

  public ProdCredentialsGuard(KeycloakProperties keycloak) {
    if (!keycloak.useClientCredentials()) {
      throw new IllegalStateException(
          "Refusing to start with the 'prod' profile: KEYCLOAK_ADMIN_CLIENT_SECRET is not set, so the"
              + " Keycloak admin client would fall back to the default admin/admin password login."
              + " Set KEYCLOAK_ADMIN_CLIENT_SECRET (and KEYCLOAK_ADMIN_CLIENT_ID if it is not admin-cli).");
    }
  }
}
