package com.umutvarol.restaurant.printing;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    @EntityGraph(attributePaths = "printer")
    List<PrintJob> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(PrintJob.Status status, Instant now, Limit limit);

    @EntityGraph(attributePaths = "printer")
    List<PrintJob> findByOrderIdOrderByIdAsc(Long orderId);

    @EntityGraph(attributePaths = "printer")
    List<PrintJob> findTop30ByOrderByIdDesc();
}
