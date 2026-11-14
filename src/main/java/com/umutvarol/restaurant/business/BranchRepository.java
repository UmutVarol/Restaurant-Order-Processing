package com.umutvarol.restaurant.business;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    List<Branch> findAllByOrderByIdAsc();

    Optional<Branch> findFirstByActiveTrueOrderByIdAsc();

    Optional<Branch> findByCode(String code);
}
