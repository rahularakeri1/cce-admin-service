package org.openphc.cce.admin.controller;

import java.time.Instant;
import java.util.List;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.MeResponse;
import org.openphc.cce.admin.dto.MenuItemResponse;
import org.openphc.cce.admin.security.CurrentUserProvider;
import org.openphc.cce.admin.service.MeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own profile and menus, resolved from the JWT (docs/api-reference.md → Current user). */
@RestController
@RequestMapping("/admin/v1/me")
public class MeController {

  private final MeService meService;
  private final CurrentUserProvider currentUserProvider;

  public MeController(MeService meService, CurrentUserProvider currentUserProvider) {
    this.meService = meService;
    this.currentUserProvider = currentUserProvider;
  }

  @GetMapping
  public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.of(meService.profile(currentUserProvider.get(), authTime(jwt)));
  }

  @GetMapping("/menus")
  public ApiResponse<List<MenuItemResponse>> menus() {
    return ApiResponse.of(meService.menus(currentUserProvider.get()));
  }

  /** auth_time arrives as epoch seconds from Keycloak; null when the client scope omits it. */
  static Instant authTime(Jwt jwt) {
    Object claim = jwt.getClaims().get("auth_time");
    if (claim instanceof Instant instant) {
      return instant;
    }
    if (claim instanceof Number seconds) {
      return Instant.ofEpochSecond(seconds.longValue());
    }
    return null;
  }
}
