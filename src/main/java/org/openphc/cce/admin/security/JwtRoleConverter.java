package org.openphc.cce.admin.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Reads roles from a configurable, dot-notation claim path (B-11). Default
 * "realm_access.roles" matches gateway-service's own default — see
 * docs/architecture-overview.md §9.2 for why this isn't yet confirmed against every real realm.
 * Missing/malformed claims resolve to an empty role list rather than throwing.
 */
@Component
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final String rolesClaimPath;

  public JwtRoleConverter(@Value("${jwt.roles-claim-path:realm_access.roles}") String rolesClaimPath) {
    this.rolesClaimPath = rolesClaimPath;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    for (String role : extractRoles(jwt)) {
      authorities.add(new SimpleGrantedAuthority(role));
    }
    return new JwtAuthenticationToken(jwt, authorities);
  }

  @SuppressWarnings("unchecked")
  List<String> extractRoles(Jwt jwt) {
    String[] segments = rolesClaimPath.split("\\.");
    Object current = jwt.getClaims();

    for (int i = 0; i < segments.length - 1; i++) {
      if (!(current instanceof Map<?, ?> map)) {
        return List.of();
      }
      current = map.get(segments[i]);
      if (current == null) {
        return List.of();
      }
    }

    if (!(current instanceof Map<?, ?> finalMap)) {
      return List.of();
    }
    Object rolesValue = finalMap.get(segments[segments.length - 1]);
    if (rolesValue instanceof List<?> list) {
      List<String> roles = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof String s) {
          roles.add(s);
        }
      }
      return roles;
    }
    return List.of();
  }
}
