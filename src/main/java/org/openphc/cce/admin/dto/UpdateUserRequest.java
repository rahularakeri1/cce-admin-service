package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Profile fields only — username is permanent, and status/roles have their own endpoints. */
public record UpdateUserRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 255) String facilityScope) {}
