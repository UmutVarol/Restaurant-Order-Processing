package com.umutvarol.restaurant.staff;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    /** Shifts that overlap [from, to), earliest first. */
    @EntityGraph(attributePaths = "user")
    List<Shift> findByStartsAtBeforeAndEndsAtAfterOrderByStartsAtAsc(Instant to, Instant from);

    List<Shift> findByUserIdAndStartsAtBeforeAndEndsAtAfter(Long userId, Instant to, Instant from);
}
