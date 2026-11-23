package org.openphc.cce.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.openphc.cce.admin.entity.Role;

public record RoleResponse(
    UUID id,
    String name,
    String displayName,
    String description,
    boolean isSystem,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static RoleResponse from(Role role) {
    return new RoleResponse(
        role.getId(),
        role.getName(),
        role.getDisplayName(),
        role.getDescription(),
        role.isSystem(),
        role.getCreatedAt(),
        role.getUpdatedAt());
  }
}
