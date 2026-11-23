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
@Table(name = "role_menus")
@IdClass(RoleMenuId.class)
public class RoleMenu {

  @Id
  @Column(name = "role_id")
  private UUID roleId;

  @Id
  @Column(name = "menu_id")
  private UUID menuId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  public RoleMenu() {}

  public RoleMenu(UUID roleId, UUID menuId) {
    this.roleId = roleId;
    this.menuId = menuId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public void setRoleId(UUID roleId) {
    this.roleId = roleId;
  }

  public UUID getMenuId() {
    return menuId;
  }

  public void setMenuId(UUID menuId) {
    this.menuId = menuId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
