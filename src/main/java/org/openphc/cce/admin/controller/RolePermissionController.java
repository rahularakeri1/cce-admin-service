package org.openphc.cce.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.PermissionResponse;
import org.openphc.cce.admin.dto.PermissionsResponse;
import org.openphc.cce.admin.dto.ReplacePermissionsRequest;
import org.openphc.cce.admin.security.CurrentUserProvider;
import org.openphc.cce.admin.service.GatewaySyncService;
import org.openphc.cce.admin.service.RolePermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A role's permissions and the gateway sync (docs/api-reference.md → Roles). Not @Transactional
 * itself: RolePermissionService commits first, THEN the sync runs, so a sync failure (502) can
 * never undo the saved rows (docs/flow-diagrams.md Flow 4).
 */
@RestController
@RequestMapping("/admin/v1/roles/{id}")
public class RolePermissionController {

  private final RolePermissionService permissionService;
  private final GatewaySyncService syncService;
  private final CurrentUserProvider currentUser;

  public RolePermissionController(
      RolePermissionService permissionService, GatewaySyncService syncService, CurrentUserProvider currentUser) {
    this.permissionService = permissionService;
    this.syncService = syncService;
    this.currentUser = currentUser;
  }

  @GetMapping("/permissions")
  public ApiResponse<PermissionsResponse> get(@PathVariable UUID id) {
    List<PermissionResponse> rows = permissionService.get(id);
    return ApiResponse.of(PermissionsResponse.of(rows));
  }

  @PutMapping("/permissions")
  public ApiResponse<PermissionsResponse> replace(
      @PathVariable UUID id, @Valid @RequestBody ReplacePermissionsRequest body, HttpServletRequest request) {
    RolePermissionService.Saved saved =
        permissionService.replace(id, body.permissions(), currentUser.get().id(), request.getRemoteAddr());
    syncService.syncFor(saved.roleName()); // throws SyncFailedException -> 502, rows already committed
    return ApiResponse.of(PermissionsResponse.of(saved.permissions()).synced());
  }

  /** Re-runs the sync without touching any row — the manual "Re-sync" (scenario S6). */
  @PostMapping("/sync")
  public ApiResponse<PermissionsResponse> resync(@PathVariable UUID id) {
    String roleName = permissionService.roleName(id);
    syncService.syncFor(roleName);
    return ApiResponse.of(PermissionsResponse.of(permissionService.get(id)).synced());
  }
}
