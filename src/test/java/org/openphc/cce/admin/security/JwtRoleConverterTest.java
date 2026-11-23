package org.openphc.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleConverterTest {

  @Test
  void extractsRolesFromDefaultRealmAccessPath() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");
    Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("FACILITY_ADMIN", "SUPER_ADMIN"))));

    assertThat(converter.extractRoles(jwt)).containsExactly("FACILITY_ADMIN", "SUPER_ADMIN");
  }

  @Test
  void extractsRolesFromNestedClientRolePath() {
    JwtRoleConverter converter = new JwtRoleConverter("resource_access.gateway-service.roles");
    Jwt jwt =
        jwtWithClaims(
            Map.of(
                "resource_access",
                Map.of("gateway-service", Map.of("roles", List.of("INSIGHTS_READ")))));

    assertThat(converter.extractRoles(jwt)).containsExactly("INSIGHTS_READ");
  }

  @Test
  void returnsEmptyListWhenClaimIsMissingEntirely() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");
    Jwt jwt = jwtWithClaims(Map.of());

    assertThat(converter.extractRoles(jwt)).isEmpty();
  }

  @Test
  void returnsEmptyListWhenIntermediateSegmentIsNotAMap() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");
    Jwt jwt = jwtWithClaims(Map.of("realm_access", "not-a-map"));

    assertThat(converter.extractRoles(jwt)).isEmpty();
  }

  @Test
  void returnsEmptyListWhenRolesValueIsNotAList() {
    JwtRoleConverter converter = new JwtRoleConverter("realm_access.roles");
    Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", "not-a-list")));

    assertThat(converter.extractRoles(jwt)).isEmpty();
  }

  private Jwt jwtWithClaims(Map<String, Object> extraClaims) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claims(claims -> claims.putAll(extraClaims))
        .build();
  }
}
