package org.openphc.cce.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.entity.User;

public record UserResponse(
    UUID id,
    String username,
    String email,
    String firstName,
    String lastName,
    String status,
    List<String> roles,
    String facilityScope,
    OffsetDateTime lastLoginAt,
    OffsetDateTime createdAt) {

  public static UserResponse from(User u, List<String> roleNames) {
    return new UserResponse(
        u.getId(),
        u.getUsername(),
        u.getEmail(),
        u.getFirstName(),
        u.getLastName(),
        u.getStatus().name(),
        roleNames,
        u.getFacilityScope(),
        u.getLastLoginAt(),
        u.getCreatedAt());
  }
}
