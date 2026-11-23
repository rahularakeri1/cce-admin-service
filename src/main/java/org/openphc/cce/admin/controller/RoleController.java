package org.openphc.cce.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.CreateRoleRequest;
import org.openphc.cce.admin.dto.RoleMenusRequest;
import org.openphc.cce.admin.dto.RoleMenusResponse;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.dto.UpdateRoleRequest;
import org.openphc.cce.admin.security.CurrentUserProvider;
import org.openphc.cce.admin.service.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Roles and their menu assignment (docs/api-reference.md → Roles). Permissions come in Milestone 4. */
@RestController
@RequestMapping("/admin/v1/roles")
public class RoleController {

  private final RoleService roleService;
  private final CurrentUserProvider currentUser;

  public RoleController(RoleService roleService, CurrentUserProvider currentUser) {
    this.roleService = roleService;
    this.currentUser = currentUser;
  }

  @GetMapping
  public ApiResponse<List<RoleResponse>> list() {
    return ApiResponse.of(roleService.list());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<RoleResponse> create(@Valid @RequestBody CreateRoleRequest body, HttpServletRequest request) {
    return ApiResponse.of(roleService.create(body, currentUser.get().id(), request.getRemoteAddr()));
  }

  @GetMapping("/{id}")
  public ApiResponse<RoleResponse> get(@PathVariable UUID id) {
    return ApiResponse.of(roleService.get(id));
  }

  @PutMapping("/{id}")
  public ApiResponse<RoleResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest body, HttpServletRequest request) {
    return ApiResponse.of(roleService.update(id, body, currentUser.get().id(), request.getRemoteAddr()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, HttpServletRequest request) {
    roleService.delete(id, currentUser.get().id(), request.getRemoteAddr());
  }

  @GetMapping("/{id}/menus")
  public ApiResponse<RoleMenusResponse> menus(@PathVariable UUID id) {
    return ApiResponse.of(roleService.menus(id));
  }

  @PutMapping("/{id}/menus")
  public ApiResponse<RoleMenusResponse> replaceMenus(
      @PathVariable UUID id, @Valid @RequestBody RoleMenusRequest body, HttpServletRequest request) {
    return ApiResponse.of(roleService.replaceMenus(id, body.menuIds(), currentUser.get().id(), request.getRemoteAddr()));
  }
}
