package org.openphc.cce.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Full-replacement payload: always the complete desired permission set for the role. */
public record ReplacePermissionsRequest(@NotNull @Valid List<PermissionInput> permissions) {}
