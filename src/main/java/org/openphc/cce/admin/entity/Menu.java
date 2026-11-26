package org.openphc.cce.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Flat console-screen catalog entry — no NAV/DETAIL distinction, no parent_id (simplified from the PRD, see docs/data-dictionary.md §1.3). */
@Entity
@Table(name = "menus")
public class Menu {

  public static final String CONSOLE_INSIGHTS = "INSIGHTS";
  public static final String CONSOLE_ADMIN = "ADMIN";

  @Id private UUID id;

  @Column(name = "menu_key", nullable = false)
  private String menuKey;

  @Column(nullable = false)
  private String label;

  @Column(nullable = false)
  private String path;

  private String icon;

  /** INSIGHTS (cce-insights-ui) or ADMIN (this console's own tabs) — see {@link #CONSOLE_ADMIN}. */
  @Column(nullable = false)
  private String console = CONSOLE_INSIGHTS;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder = 0;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getMenuKey() {
    return menuKey;
  }

  public void setMenuKey(String menuKey) {
    this.menuKey = menuKey;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getIcon() {
    return icon;
  }

  public void setIcon(String icon) {
    this.icon = icon;
  }

  public String getConsole() {
    return console;
  }

  public void setConsole(String console) {
    this.console = console;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
