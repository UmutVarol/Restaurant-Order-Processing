package com.umutvarol.restaurant.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TipPoolRepository extends JpaRepository<TipPool, Long> {

    @EntityGraph(attributePaths = {"shares", "shares.user"})
    Optional<TipPool> findByBusinessDateAndBranchId(LocalDate date, Long branchId);

    @EntityGraph(attributePaths = {"shares", "shares.user"})
    List<TipPool> findByBusinessDateBetweenOrderByBusinessDateDesc(LocalDate from, LocalDate to);
}
