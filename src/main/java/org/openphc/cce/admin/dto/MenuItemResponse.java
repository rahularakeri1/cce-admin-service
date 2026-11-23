package org.openphc.cce.admin.dto;

import java.util.UUID;
import org.openphc.cce.admin.entity.Menu;

/** One entry of GET /me/menus — flat, no nesting (docs/data-dictionary.md §1.3). */
public record MenuItemResponse(UUID id, String label, String path, String icon) {

  public static MenuItemResponse from(Menu menu) {
    return new MenuItemResponse(menu.getId(), menu.getLabel(), menu.getPath(), menu.getIcon());
  }
}
