package org.openphc.cce.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * Mirrors gateway-service's existing entity field-for-field. This service never creates or
 * alters this table's DDL — it already exists, owned by gateway-service. BIGSERIAL id is the one
 * deliberate exception among the seven tables (see docs/architecture-overview.md §9.1).
 */
@Entity
@Table(name = "api_permissions")
public class ApiPermission {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "permission_name", nullable = false)
  private String permissionName;

  @Column(name = "http_method", nullable = false)
  private String httpMethod;

  @Column(name = "uri_pattern", nullable = false)
  private String uriPattern;

  private String description;

  @Column(name = "resource_category")
  private String resourceCategory;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getPermissionName() {
    return permissionName;
  }

  public void setPermissionName(String permissionName) {
    this.permissionName = permissionName;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public void setHttpMethod(String httpMethod) {
    this.httpMethod = httpMethod;
  }

  public String getUriPattern() {
    return uriPattern;
  }

  public void setUriPattern(String uriPattern) {
    this.uriPattern = uriPattern;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getResourceCategory() {
    return resourceCategory;
  }

  public void setResourceCategory(String resourceCategory) {
    this.resourceCategory = resourceCategory;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
