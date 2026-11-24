package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Username and email are lower-cased server-side (Keycloak does the same, and the JWT email claim
 * is matched against the local row, so a mixed-case local email would lock the user out with a 403).
 */
public record CreateUserRequest(
    @NotBlank
        @Size(min = 3, max = 100)
        @Pattern(regexp = "^[A-Za-z0-9._@+-]+$", message = "may contain letters, digits and . _ @ + - only")
        String username,
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @Size(max = 255) String facilityScope,
    @NotNull List<UUID> roleIds) {}
