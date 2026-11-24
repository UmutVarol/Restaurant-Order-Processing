package com.umutvarol.restaurant.compliance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdisyonRepository extends JpaRepository<Adisyon, Long> {

    Optional<Adisyon> findByBillId(Long billId);

    List<Adisyon> findByDeliveryInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Collection<Adisyon.Delivery> deliveries,
                                                                                       Instant due);

    List<Adisyon> findByOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtDesc(Instant from, Instant to);

    List<Adisyon> findByDeliveryIn(Collection<Adisyon.Delivery> deliveries);

    long countByDeliveryIn(Collection<Adisyon.Delivery> deliveries);
}
