package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/**
 * Body of PUT /menus/order: the COMPLETE ordered list of one console's entries, never a partial move (see
 * MenuService.reorder for why).
 */
public record ReorderMenusRequest(
    @NotBlank @Pattern(regexp = "^(INSIGHTS|ADMIN)$", message = "must be INSIGHTS or ADMIN") String console,
    @NotNull List<UUID> menuIds) {}
