package org.openphc.cce.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST and PUT /menus. A "console" in the body is deliberately ignored, not rejected: new entries are
 * always INSIGHTS (admin tabs need a screen built in code, so they come from migrations), and an existing
 * entry's console never changes (docs/api-reference.md → Menus).
 *
 * <p>On create, a missing sortOrder is 0 and a missing isActive is true. On update, a missing sortOrder or
 * isActive means "leave as it is".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuRequest(
    @NotBlank
        @Size(max = 100)
        @Pattern(
            regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case letters, digits, '-' or '_' (e.g. reports or admin-audit)")
        String menuKey,
    @NotBlank @Size(max = 150) String label,
    @NotBlank @Size(max = 255) @Pattern(regexp = "^/.*", message = "must start with '/'") String path,
    @Size(max = 100) String icon,
    @Min(0) Integer sortOrder,
    Boolean isActive) {}
