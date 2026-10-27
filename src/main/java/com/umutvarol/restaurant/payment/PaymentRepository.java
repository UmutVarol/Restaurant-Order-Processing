package com.umutvarol.restaurant.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByProviderToken(String providerToken);

    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);

    List<Payment> findByLinkIdAndStatus(Long linkId, PaymentStatus status);

    @EntityGraph(attributePaths = {"refunds", "bill"})
    List<Payment> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);
}
