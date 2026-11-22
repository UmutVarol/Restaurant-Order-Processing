package com.umutvarol.restaurant.directory;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueCheckinRepository extends JpaRepository<VenueCheckin, Long> {

    @Query("SELECT COUNT(DISTINCT c.deviceHash) FROM VenueCheckin c WHERE c.createdAt >= :since")
    long countDevicesSince(@Param("since") Instant since);

    List<VenueCheckin> findByCreatedAtGreaterThanEqual(Instant since);

    boolean existsByDeviceHashAndCreatedAtBefore(String deviceHash, Instant before);
}
