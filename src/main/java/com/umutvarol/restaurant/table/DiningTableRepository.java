package com.umutvarol.restaurant.table;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

    List<DiningTable> findAllByOrderByIdAsc();

    Optional<DiningTable> findByQrTokenHash(String qrTokenHash);
}
