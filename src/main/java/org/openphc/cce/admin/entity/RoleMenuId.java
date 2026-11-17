package org.openphc.cce.admin.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class RoleMenuId implements Serializable {

  private UUID roleId;
  private UUID menuId;

  public RoleMenuId() {}

  public RoleMenuId(UUID roleId, UUID menuId) {
    this.roleId = roleId;
    this.menuId = menuId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RoleMenuId that)) return false;
    return Objects.equals(roleId, that.roleId) && Objects.equals(menuId, that.menuId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(roleId, menuId);
  }
}
