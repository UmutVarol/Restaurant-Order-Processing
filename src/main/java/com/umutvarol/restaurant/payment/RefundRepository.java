package com.umutvarol.restaurant.payment;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByStatus(RefundStatus status);

    /**
     * Claims a pending refund for sending, atomically in the database, so that only one server (or one attempt)
     * sends it to the provider at a time, even when several app instances run.
     *
     * @return 1 if this caller won the claim
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Refund r SET r.claimedUntil = :until
            WHERE r.id = :id AND r.status = com.umutvarol.restaurant.payment.RefundStatus.PENDING
              AND (r.claimedUntil IS NULL OR r.claimedUntil < :now)""")
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("until") Instant until);

    @EntityGraph(attributePaths = "payment")
    List<Refund> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}
