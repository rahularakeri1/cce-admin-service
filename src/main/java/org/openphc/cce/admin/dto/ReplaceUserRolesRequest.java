package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Full-replacement payload: the complete desired role set (empty clears every role). */
public record ReplaceUserRolesRequest(@NotNull List<UUID> roleIds) {}
