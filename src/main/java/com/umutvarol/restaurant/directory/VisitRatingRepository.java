package com.umutvarol.restaurant.directory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRatingRepository extends JpaRepository<VisitRating, Long> {

    Optional<VisitRating> findByBillId(Long billId);

    List<VisitRating> findAllByOrderByCreatedAtDesc(Pageable page);

    List<VisitRating> findByBranchIdOrderByCreatedAtDesc(Long branchId, Pageable page);

    @Query("SELECT COUNT(r), COALESCE(AVG(r.stars), 0) FROM VisitRating r WHERE r.createdAt >= :since")
    List<Object[]> summarySince(@Param("since") Instant since);

    @Query("SELECT COUNT(r), COALESCE(AVG(r.stars), 0) FROM VisitRating r WHERE r.branchId = :branch")
    List<Object[]> summaryOfBranch(@Param("branch") Long branch);
}
