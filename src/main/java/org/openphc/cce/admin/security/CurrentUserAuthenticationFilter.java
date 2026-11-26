package org.openphc.cce.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the JWT's email claim to a local user, enforces ACTIVE status (B-12) and loads that user's
 * database roles into {@link CurrentUser} (see {@link SuperAdminAuthorizationManager}). Simplified
 * from the PRD's sub/keycloak_id join — see docs/data-dictionary.md §1.1 for the accepted
 * trade-off. Runs after Spring Security has already authenticated the JWT itself.
 */
@Component
public class CurrentUserAuthenticationFilter extends OncePerRequestFilter {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;

  public CurrentUserAuthenticationFilter(UserRepository userRepository, RoleRepository roleRepository) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
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

    // The roles in the DATABASE, read on every request, not the token's claim: an admin's change takes
    // effect on the very next call instead of when the user's token refreshes (docs/architecture-overview.md §9.9).
    List<String> roles = roleRepository.findNamesByUserId(user.getId());

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
