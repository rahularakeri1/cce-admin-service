package org.openphc.cce.admin.controller;

import java.util.List;
import org.openphc.cce.admin.dto.ApiResponse;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.repository.MenuRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only for now: the Role Detail → Menus tab needs the full catalog. Create/update/delete of
 * menus arrive with Milestone 9 (Menus Catalog).
 */
@RestController
@RequestMapping("/admin/v1/menus")
public class MenuController {

  private final MenuRepository menuRepository;

  public MenuController(MenuRepository menuRepository) {
    this.menuRepository = menuRepository;
  }

  @GetMapping
  public ApiResponse<List<MenuResponse>> list() {
    // Insights menus first, then the admin console's own tabs (console DESC: 'INSIGHTS' sorts after 'ADMIN').
    Sort order = Sort.by(Sort.Order.desc("console"), Sort.Order.asc("sortOrder"), Sort.Order.asc("label"));
    return ApiResponse.of(menuRepository.findAll(order).stream().map(MenuResponse::from).toList());
  }
}
