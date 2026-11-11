package com.umutvarol.restaurant.reservation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** A day's bookings, whatever their status, and every party still waiting or seated. */
    @EntityGraph(attributePaths = "table")
    @Query("""
            select r from Reservation r
            where (r.kind = com.umutvarol.restaurant.reservation.ReservationKind.BOOKING
                   and r.startsAt >= :from and r.startsAt < :to)
               or r.status in (com.umutvarol.restaurant.reservation.ReservationStatus.WAITING,
                               com.umutvarol.restaurant.reservation.ReservationStatus.SEATED)
            order by r.startsAt asc, r.id asc""")
    List<Reservation> forDay(@Param("from") Instant from, @Param("to") Instant to);

    @EntityGraph(attributePaths = "table")
    List<Reservation> findByTableIdAndStatusIn(Long tableId, Collection<ReservationStatus> statuses);

    @EntityGraph(attributePaths = "table")
    List<Reservation> findByStatusAndTableIsNotNull(ReservationStatus status);

    @EntityGraph(attributePaths = "table")
    List<Reservation> findByStatusAndTableIsNotNullAndStartsAtBetweenOrderByStartsAtAsc(
            ReservationStatus status, Instant from, Instant to);

    List<Reservation> findByStatusAndStartsAtBefore(ReservationStatus status, Instant before);

    List<Reservation> findByPhoneIsNotNullAndStatusInAndStartsAtBefore(Collection<ReservationStatus> statuses,
                                                                      Instant before);
}
