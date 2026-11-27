package org.openphc.cce.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.dto.ReorderMenusRequest;
import org.openphc.cce.admin.security.CurrentUserProvider;
import org.openphc.cce.admin.service.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The menu catalog (docs/api-reference.md → Menus): managing the entries themselves, not any role's selection
 * from them. SUPER_ADMIN only, like everything under /admin/v1 except /me and /health.
 */
@RestController
@RequestMapping("/admin/v1/menus")
public class MenuController {

  private final MenuService menuService;
  private final CurrentUserProvider currentUser;

  public MenuController(MenuService menuService, CurrentUserProvider currentUser) {
    this.menuService = menuService;
    this.currentUser = currentUser;
  }

  @GetMapping
  public ApiResponse<List<MenuResponse>> list() {
    return ApiResponse.of(menuService.list());
  }

  /** Arranges one console's entries (docs/api-reference.md → Menus). A literal path wins over /{id}, so "order" is never an id. */
  @PutMapping("/order")
  public ApiResponse<List<MenuResponse>> reorder(@Valid @RequestBody ReorderMenusRequest body, HttpServletRequest request) {
    return ApiResponse.of(menuService.reorder(body.console(), body.menuIds(), currentUser.get().id(), request.getRemoteAddr()));
  }

  @PutMapping("/{id}")
  public ApiResponse<MenuResponse> update(
      @PathVariable UUID id, @Valid @RequestBody MenuRequest body, HttpServletRequest request) {
    return ApiResponse.of(menuService.update(id, body, currentUser.get().id(), request.getRemoteAddr()));
  }
}
