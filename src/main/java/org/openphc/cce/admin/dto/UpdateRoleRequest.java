package org.openphc.cce.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A "name" in the body is deliberately ignored, not rejected (docs/api-reference.md → PUT /roles/:id). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateRoleRequest(@NotBlank @Size(max = 150) String displayName, String description) {}
