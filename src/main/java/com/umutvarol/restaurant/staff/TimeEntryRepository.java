package com.umutvarol.restaurant.staff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {

    Optional<TimeEntry> findByOpenUserId(Long userId);

    @EntityGraph(attributePaths = "user")
    List<TimeEntry> findByOpenUserIdIsNotNull();

    /** Entries that overlap [from, to), open ones included. */
    @EntityGraph(attributePaths = "user")
    @Query("""
            select e from TimeEntry e
            where e.clockedInAt < :to and (e.clockedOutAt is null or e.clockedOutAt > :from)
            order by e.clockedInAt asc""")
    List<TimeEntry> overlapping(@Param("from") Instant from, @Param("to") Instant to);
}
