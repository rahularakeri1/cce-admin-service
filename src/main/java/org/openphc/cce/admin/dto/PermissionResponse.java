package org.openphc.cce.admin.dto;

import org.openphc.cce.admin.entity.ApiPermission;

public record PermissionResponse(
    Long id, String httpMethod, String uriPattern, String description, String category) {

  public static PermissionResponse from(ApiPermission p) {
    return new PermissionResponse(
        p.getId(), p.getHttpMethod(), p.getUriPattern(), p.getDescription(), p.getResourceCategory());
  }
}
