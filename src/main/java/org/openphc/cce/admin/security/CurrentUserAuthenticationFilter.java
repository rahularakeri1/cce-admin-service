package org.openphc.cce.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the JWT's email claim to a local user and enforces ACTIVE status (B-12). Simplified
 * from the PRD's sub/keycloak_id join — see docs/data-dictionary.md §1.1 for the accepted
 * trade-off. Runs after Spring Security has already authenticated the JWT itself.
 */
@Component
public class CurrentUserAuthenticationFilter extends OncePerRequestFilter {

  private final UserRepository userRepository;

  public CurrentUserAuthenticationFilter(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      // Unauthenticated path (e.g. /admin/v1/health) — nothing to resolve.
      filterChain.doFilter(request, response);
      return;
    }

    String email = jwt.getClaimAsString("email");
    if (email == null) {
      throw new AccessDeniedException("Token has no email claim");
    }

    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new AccessDeniedException("No local user for email " + email));

    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new AccessDeniedException("User is not ACTIVE: " + email);
    }

    List<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

    CurrentUser currentUser = new CurrentUser(user.getId(), user.getUsername(), roles, user.getFacilityScope());
    request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, currentUser);

    filterChain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return path.equals("/admin/v1/health")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/v3/api-docs");
  }
}
