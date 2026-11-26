package org.openphc.cce.admin.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.dto.MeResponse;
import org.openphc.cce.admin.dto.MenuItemResponse;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.exception.NotFoundException;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Backs GET /me and GET /me/menus. Roles come from the local user_roles assignment. */
@Service
public class MeService {

  /** Fallback throttle when the token has no auth_time claim. */
  static final Duration FALLBACK_THROTTLE = Duration.ofMinutes(5);

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final RoleRepository roleRepository;
  private final MenuUnionService menuUnionService;

  public MeService(
      UserRepository userRepository,
      UserRoleRepository userRoleRepository,
      RoleRepository roleRepository,
      MenuUnionService menuUnionService) {
    this.userRepository = userRepository;
    this.userRoleRepository = userRoleRepository;
    this.roleRepository = roleRepository;
    this.menuUnionService = menuUnionService;
  }

  /**
   * Returns the caller's profile and records the login. {@code authTime} is the JWT's auth_time
   * (the real login moment, constant across token refreshes); null when the token lacks it.
   */
  @Transactional
  public MeResponse profile(CurrentUser current, Instant authTime) {
    User user =
        userRepository.findById(current.id()).orElseThrow(() -> NotFoundException.user(current.id()));
    recordLogin(user, authTime, Instant.now());
    return new MeResponse(user.getId(), user.getUsername(), roleNames(user.getId()), user.getFacilityScope());
  }

  /** The caller's menus for one console ({@link Menu#CONSOLE_INSIGHTS} or {@link Menu#CONSOLE_ADMIN}). */
  @Transactional(readOnly = true)
  public List<MenuItemResponse> menus(CurrentUser current, String console) {
    List<UUID> roleIds = roleIds(current.id());
    return menuUnionService.menusForRoles(roleIds).stream()
        .filter(menu -> console.equals(menu.getConsole()))
        .map(MenuItemResponse::from)
        .toList();
  }

  /**
   * With auth_time: write only when this login is newer than the stored one, so one session never
   * writes twice and a quick re-login still registers. Without it: fall back to now(), throttled
   * to one write per 5 minutes.
   */
  private void recordLogin(User user, Instant authTime, Instant now) {
    OffsetDateTime last = user.getLastLoginAt();
    Instant target = authTime != null ? authTime : now;
    boolean due =
        last == null
            || (authTime != null
                ? last.toInstant().isBefore(authTime)
                : last.toInstant().isBefore(now.minus(FALLBACK_THROTTLE)));
    if (due) {
      userRepository.touchLastLogin(user.getId(), OffsetDateTime.ofInstant(target, ZoneOffset.UTC));
    }
  }

  private List<UUID> roleIds(UUID userId) {
    return userRoleRepository.findAllByUserId(userId).stream().map(UserRole::getRoleId).toList();
  }

  private List<String> roleNames(UUID userId) {
    return roleRepository.findAllById(roleIds(userId)).stream().map(Role::getName).sorted().toList();
  }
}
