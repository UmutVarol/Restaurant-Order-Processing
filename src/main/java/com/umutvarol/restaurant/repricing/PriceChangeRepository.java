package com.umutvarol.restaurant.repricing;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PriceChangeRepository extends JpaRepository<PriceChange, Long> {

    List<PriceChange> findAllByOrderByCreatedAtDesc(Pageable page);
}
