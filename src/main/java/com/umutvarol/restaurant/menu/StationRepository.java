package com.umutvarol.restaurant.menu;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationRepository extends JpaRepository<Station, Long> {

    List<Station> findAllByOrderBySortOrderAscIdAsc();

    Optional<Station> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
