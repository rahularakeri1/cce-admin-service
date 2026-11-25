package org.openphc.cce.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.security.CurrentUserProvider;
import org.openphc.cce.admin.dto.ReplaceUserRolesRequest;
import org.openphc.cce.admin.dto.UpdateUserRequest;
import org.openphc.cce.admin.dto.UpdateUserStatusRequest;
import org.openphc.cce.admin.service.UserCreationService;
import org.openphc.cce.admin.service.UserManagementService;
import org.openphc.cce.admin.service.UserQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Users — list and create (Milestone 5); fetch, edit, status, roles and reset-password (Milestone 6). */
@RestController
@RequestMapping("/admin/v1/users")
public class UserController {

  private final UserCreationService creationService;
  private final UserQueryService queryService;
  private final UserManagementService managementService;
  private final CurrentUserProvider currentUser;

  public UserController(
      UserCreationService creationService,
      UserQueryService queryService,
      UserManagementService managementService,
      CurrentUserProvider currentUser) {
    this.creationService = creationService;
    this.queryService = queryService;
    this.managementService = managementService;
    this.currentUser = currentUser;
  }

  @GetMapping
  public ApiResponse<List<UserResponse>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID roleId,
      @RequestParam(required = false) String facilityScope,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize) {
    UserQueryService.Result result = queryService.list(status, roleId, facilityScope, q, page, pageSize);
    return ApiResponse.of(result.users(), result.pagination());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest body, HttpServletRequest request) {
    UserCreationService.Created created = creationService.create(body, currentUser.get().id(), request.getRemoteAddr());
    ApiResponse<UserResponse> response = ApiResponse.of(created.user());
    return created.warnings().isEmpty() ? response : response.withWarnings(created.warnings());
  }

  @GetMapping("/{id}")
  public ApiResponse<UserResponse> get(@PathVariable UUID id) {
    return ApiResponse.of(queryService.get(id));
  }

  @PutMapping("/{id}")
  public ApiResponse<UserResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest body, HttpServletRequest request) {
    UserManagementService.Result result = managementService.updateProfile(id, body, currentUser.get().id(), request.getRemoteAddr());
    ApiResponse<UserResponse> response = ApiResponse.of(result.user());
    return result.warnings().isEmpty() ? response : response.withWarnings(result.warnings());
  }

  @PatchMapping("/{id}/status")
  public ApiResponse<UserResponse> setStatus(
      @PathVariable UUID id, @Valid @RequestBody UpdateUserStatusRequest body, HttpServletRequest request) {
    return ApiResponse.of(managementService.setStatus(id, body.status(), currentUser.get().id(), request.getRemoteAddr()));
  }

  @PutMapping("/{id}/roles")
  public ApiResponse<UserResponse> replaceRoles(
      @PathVariable UUID id, @Valid @RequestBody ReplaceUserRolesRequest body, HttpServletRequest request) {
    return ApiResponse.of(managementService.replaceRoles(id, body, currentUser.get().id(), request.getRemoteAddr()));
  }

  /** Resend or genuine reset: the same email as creation, no password passes through this service. */
  @PostMapping("/{id}/reset-password")
  public ApiResponse<java.util.Map<String, Boolean>> resetPassword(@PathVariable UUID id, HttpServletRequest request) {
    managementService.resetPassword(id, currentUser.get().id(), request.getRemoteAddr());
    return ApiResponse.of(java.util.Map.of("sent", true));
  }
}
