package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * name is permanent and doubles as the Keycloak realm role name and api_permissions.permission_name,
 * so it is strictly UPPER_SNAKE_CASE: letters/digits in upper case, single underscores between
 * words, starting with a letter (docs/data-dictionary.md §1.2).
 */
public record CreateRoleRequest(
    @NotBlank
        @Size(max = 100)
        @Pattern(
            regexp = "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$",
            message = "must be UPPER_SNAKE_CASE (e.g. FACILITY_ADMIN)")
        String name,
    @NotBlank @Size(max = 150) String displayName,
    String description) {}
