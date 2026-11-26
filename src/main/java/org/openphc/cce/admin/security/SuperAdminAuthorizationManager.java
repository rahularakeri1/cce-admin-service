package org.openphc.cce.admin.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Management endpoints (users, roles, menus, permissions) need SUPER_ADMIN, decided on the roles
 * CurrentUserAuthenticationFilter loaded from the database — so the service refuses what a role may not
 * do even when the gateway is bypassed or lets the request through. No CurrentUser on the request (an
 * anonymous call) denies, which Spring turns into 401.
 */
@Component
public class SuperAdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

  public static final String SUPER_ADMIN = "SUPER_ADMIN";

  @Override
  public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
    Object attribute = context.getRequest().getAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE);
    boolean allowed = attribute instanceof CurrentUser user && user.roles().contains(SUPER_ADMIN);
    return new AuthorizationDecision(allowed);
  }
}
