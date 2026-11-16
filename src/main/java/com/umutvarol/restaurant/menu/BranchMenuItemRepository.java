package com.umutvarol.restaurant.menu;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchMenuItemRepository extends JpaRepository<BranchMenuItem, Long> {

    List<BranchMenuItem> findByBranchId(Long branchId);

    List<BranchMenuItem> findByMenuItemId(Long menuItemId);

    Optional<BranchMenuItem> findByBranchIdAndMenuItemId(Long branchId, Long menuItemId);
}
