package com.umutvarol.restaurant.business;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    List<Branch> findAllByOrderByIdAsc();

    Optional<Branch> findFirstByActiveTrueOrderByIdAsc();

    Optional<Branch> findByCode(String code);

    /** Listed, open branches inside a box around a guest; read across businesses by the directory. */
    List<Branch> findByListedTrueAndActiveTrueAndLatitudeBetweenAndLongitudeBetween(double minLat, double maxLat,
                                                                                   double minLng, double maxLng);

    long countByActiveTrue();
}
