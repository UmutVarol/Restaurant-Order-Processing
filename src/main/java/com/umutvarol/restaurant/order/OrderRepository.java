package com.umutvarol.restaurant.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @EntityGraph(attributePaths = "table")
    List<Order> findByStatusInOrderByCreatedAtAsc(Collection<OrderStatus> statuses);

    @EntityGraph(attributePaths = "table")
    List<Order> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    long countByStatusIn(Collection<OrderStatus> statuses);

    boolean existsByTableIdAndStatusIn(Long tableId, Collection<OrderStatus> statuses);
}
