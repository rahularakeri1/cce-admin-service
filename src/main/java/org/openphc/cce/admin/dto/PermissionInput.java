package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One row of a role's permission set: this role may call {@code httpMethod} on {@code uriPattern}. */
public record PermissionInput(
    @NotBlank @Pattern(regexp = "^(GET|POST|PUT|PATCH|DELETE)$", message = "must be GET, POST, PUT, PATCH or DELETE")
        String httpMethod,
    @NotBlank @Size(max = 512) @Pattern(regexp = "^/\\S*$", message = "must start with / and contain no spaces")
        String uriPattern,
    String description,
    @Size(max = 255) String category) {}
