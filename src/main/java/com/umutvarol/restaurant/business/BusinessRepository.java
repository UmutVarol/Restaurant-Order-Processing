package com.umutvarol.restaurant.business;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findBySlug(String slug);

    List<Business> findByStatusNotOrderByIdAsc(Business.Status status);
}
