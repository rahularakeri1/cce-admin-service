package org.openphc.cce.admin.dto;

import java.util.UUID;
import org.openphc.cce.admin.entity.Menu;

/** Full catalog entry for GET /menus (includes inactive ones, unlike GET /me/menus). */
public record MenuResponse(
    UUID id,
    String menuKey,
    String label,
    String path,
    String icon,
    String console,
    int sortOrder,
    boolean isActive) {

  public static MenuResponse from(Menu menu) {
    return new MenuResponse(
        menu.getId(),
        menu.getMenuKey(),
        menu.getLabel(),
        menu.getPath(),
        menu.getIcon(),
        menu.getConsole(),
        menu.getSortOrder(),
        menu.isActive());
  }
}
