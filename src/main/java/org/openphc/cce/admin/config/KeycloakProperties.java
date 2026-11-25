package org.openphc.cce.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The {@code keycloak.*} block of application.yml — see the comments there for the two auth modes. */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
    String serverUrl,
    String realm,
    @DefaultValue("master") String adminRealm,
    @DefaultValue("admin-cli") String adminClientId,
    @DefaultValue("") String adminClientSecret,
    @DefaultValue("admin") String adminUsername,
    @DefaultValue("admin") String adminPassword,
    @DefaultValue("259200") long inviteLifespanSeconds,
    @DefaultValue("") String inviteClientId,
    @DefaultValue("") String inviteRedirectUri) {

  public boolean useClientCredentials() {
    return adminClientSecret != null && !adminClientSecret.isBlank();
  }
}
