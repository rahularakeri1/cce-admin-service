package org.openphc.cce.admin.config;

import org.openphc.cce.admin.security.CurrentUserAuthenticationFilter;
import org.openphc.cce.admin.security.JwtRoleConverter;
import org.openphc.cce.admin.security.RestAccessDeniedHandler;
import org.openphc.cce.admin.security.RestAuthenticationEntryPoint;
import org.openphc.cce.admin.security.SuperAdminAuthorizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * /health and the Swagger/OpenAPI paths are open (B-05, B-10); /me and /me/menus need any signed-in ACTIVE
 * user; everything else under /admin/v1/** needs SUPER_ADMIN (Milestone 7). The JWT is re-validated
 * independently of gateway-service, defense in depth — see docs/architecture-overview.md §5.
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtRoleConverter jwtRoleConverter,
      CurrentUserAuthenticationFilter currentUserAuthenticationFilter,
      SuperAdminAuthorizationManager superAdminAuthorizationManager,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/admin/v1/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                    .permitAll()
                    // Any signed-in ACTIVE user: every console needs these to build its own shell.
                    .requestMatchers("/admin/v1/me", "/admin/v1/me/menus")
                    .authenticated()
                    // Everything else (users, roles, menus, permissions) is SUPER_ADMIN's.
                    .anyRequest()
                    .access(superAdminAuthorizationManager))
        .oauth2ResourceServer(
            (OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter))
                    .authenticationEntryPoint(authenticationEntryPoint))
        .exceptionHandling(handling -> handling.accessDeniedHandler(accessDeniedHandler))
        // Must sit AFTER ExceptionTranslationFilter (which precedes AuthorizationFilter) so the
        // AccessDeniedException this filter throws becomes a JSON 403, not an unhandled 500.
        .addFilterBefore(currentUserAuthenticationFilter, AuthorizationFilter.class);

    return http.build();
  }
}
