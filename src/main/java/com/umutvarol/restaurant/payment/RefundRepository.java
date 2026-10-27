package com.umutvarol.restaurant.payment;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByStatus(RefundStatus status);

    @EntityGraph(attributePaths = "payment")
    List<Refund> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}
