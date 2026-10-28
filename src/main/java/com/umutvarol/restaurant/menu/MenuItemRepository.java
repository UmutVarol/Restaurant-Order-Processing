package com.umutvarol.restaurant.menu;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByArchivedFalse();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByStationCodeAndArchivedFalse(String stationCode);
}
