package org.openphc.cce.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code syncStatus} is "SYNCED" when this response follows a successful sync, absent on a plain GET. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionsResponse(List<PermissionResponse> permissions, String syncStatus) {

  public static PermissionsResponse of(List<PermissionResponse> permissions) {
    return new PermissionsResponse(permissions, null);
  }

  public PermissionsResponse synced() {
    return new PermissionsResponse(permissions, "SYNCED");
  }
}
