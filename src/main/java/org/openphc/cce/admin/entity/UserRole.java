package org.openphc.cce.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "user_roles")
@IdClass(UserRoleId.class)
public class UserRole {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Id
  @Column(name = "role_id")
  private UUID roleId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UserRole() {}

  public UserRole(UUID userId, UUID roleId) {
    this.userId = userId;
    this.roleId = roleId;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public void setRoleId(UUID roleId) {
    this.roleId = roleId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
