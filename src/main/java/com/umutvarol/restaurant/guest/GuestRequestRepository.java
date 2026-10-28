package com.umutvarol.restaurant.guest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestRequestRepository extends JpaRepository<GuestRequest, Long> {

    @EntityGraph(attributePaths = "table")
    List<GuestRequest> findByStatusOrderByCreatedAtAsc(GuestRequestStatus status);

    Optional<GuestRequest> findByPublicId(String publicId);

    long countByTableIdAndStatus(Long tableId, GuestRequestStatus status);

    Optional<GuestRequest> findFirstByTableIdAndKindAndStatus(Long tableId, GuestRequestKind kind, GuestRequestStatus status);

    boolean existsByTableIdAndKindAndCreatedAtAfter(Long tableId, GuestRequestKind kind, Instant after);
}
