package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Full-replacement payload: always the complete desired set. An empty list clears the role's menus. */
public record RoleMenusRequest(@NotNull List<UUID> menuIds) {}
