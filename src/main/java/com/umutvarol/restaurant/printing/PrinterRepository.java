package com.umutvarol.restaurant.printing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrinterRepository extends JpaRepository<Printer, Long> {

    List<Printer> findAllByOrderByNameAsc();

    List<Printer> findByEnabledTrue();

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
