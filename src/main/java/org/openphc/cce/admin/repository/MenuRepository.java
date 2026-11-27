package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, UUID> {

  List<Menu> findAllByOrderBySortOrderAsc();

  List<Menu> findAllByConsoleOrderBySortOrderAsc(String console);

  /** The highest position in one console (0 when it has none) — a new entry goes one past it. */
  @org.springframework.data.jpa.repository.Query("select coalesce(max(m.sortOrder), 0) from Menu m where m.console = :console")
  int maxSortOrder(@org.springframework.data.repository.query.Param("console") String console);

  boolean existsByMenuKey(String menuKey);
}
