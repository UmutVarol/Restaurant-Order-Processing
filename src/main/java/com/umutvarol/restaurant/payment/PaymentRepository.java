package com.umutvarol.restaurant.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByProviderToken(String providerToken);

    long countByStatus(PaymentStatus status);

    Optional<Payment> findFirstByStatusOrderByCreatedAtAsc(PaymentStatus status);

    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);

    List<Payment> findByLinkIdAndStatus(Long linkId, PaymentStatus status);

    @EntityGraph(attributePaths = {"refunds", "bill"})
    List<Payment> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);

    /** Tips on payments that went through in [from, to); refunds give back the amount, never the tip. */
    @Query("""
            select coalesce(sum(p.tip), 0) from Payment p
            where p.status = com.umutvarol.restaurant.payment.PaymentStatus.SUCCEEDED
              and p.completedAt >= :from and p.completedAt < :to and p.bill.branchId = :branch""")
    BigDecimal sumTips(@Param("from") Instant from, @Param("to") Instant to, @Param("branch") Long branch);
}
