package com.umutvarol.restaurant.fiscal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface FiscalDocumentRepository extends JpaRepository<FiscalDocument, Long> {

    Optional<FiscalDocument> findByBillId(Long billId);

    List<FiscalDocument> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<FiscalDocument.Status> statuses, Instant due);

    List<FiscalDocument> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(Instant from, Instant to);

    List<FiscalDocument> findByStatusInOrderByCreatedAtAsc(Collection<FiscalDocument.Status> statuses);

    List<FiscalDocument> findByStatusAndKindAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(FiscalDocument.Status status,
                                                                                           FiscalKind kind, Instant from, Instant to);
}
