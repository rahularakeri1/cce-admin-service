package org.openphc.cce.admin.config;

import org.openphc.cce.admin.security.CurrentUserAuthenticationFilter;
import org.openphc.cce.admin.security.JwtRoleConverter;
import org.openphc.cce.admin.security.RestAccessDeniedHandler;
import org.openphc.cce.admin.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every /admin/v1/** path is authenticated except /health and the Swagger/OpenAPI paths (B-05,
 * B-10). The JWT is re-validated independently of gateway-service, defense in depth — see
 * docs/architecture-overview.md §5.
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtRoleConverter jwtRoleConverter,
      CurrentUserAuthenticationFilter currentUserAuthenticationFilter,
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
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            (OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter))
                    .authenticationEntryPoint(authenticationEntryPoint))
        .exceptionHandling(handling -> handling.accessDeniedHandler(accessDeniedHandler))
        .addFilterAfter(currentUserAuthenticationFilter, BearerTokenAuthenticationFilter.class);

    return http.build();
  }
}
