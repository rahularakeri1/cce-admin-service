package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;
import org.openphc.cce.admin.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, UUID> {

  List<Menu> findAllByOrderBySortOrderAsc();

  boolean existsByMenuKey(String menuKey);
}
