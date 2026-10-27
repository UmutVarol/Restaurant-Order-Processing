package com.umutvarol.restaurant.payment;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DayClosingRepository extends JpaRepository<DayClosing, Long> {

    Optional<DayClosing> findByBusinessDate(LocalDate businessDate);
}
