package com.umutvarol.restaurant.reservation;

import com.umutvarol.restaurant.floor.TableBookings;
import com.umutvarol.restaurant.floor.TableStatusResponse.Party;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Puts seated parties and upcoming bookings on the floor plan. Names and party sizes only, never phone numbers. */
@Component
@Transactional(readOnly = true)
class ReservationTableBookings implements TableBookings {

    /** How far ahead the floor plan shows a table's next booking. */
    static final Duration UPCOMING = Duration.ofHours(3);

    private final ReservationRepository reservations;
    private final Clock clock;

    ReservationTableBookings(ReservationRepository reservations, Clock clock) {
        this.reservations = reservations;
        this.clock = clock;
    }

    @Override
    public Map<Long, Party> seated() {
        Map<Long, Party> byTable = new LinkedHashMap<>();
        reservations.findByStatusAndTableIsNotNull(ReservationStatus.SEATED).forEach(r ->
                byTable.putIfAbsent(r.getTable().getId(),
                        new Party(r.getId(), r.getGuestName(), r.getPartySize(), r.getSeatedAt())));
        return byTable;
    }

    @Override
    public boolean holdsTable(Long tableId) {
        return !reservations.findByTableIdAndStatusIn(tableId,
                java.util.Set.of(ReservationStatus.BOOKED, ReservationStatus.SEATED)).isEmpty();
    }

    @Override
    public Map<Long, Party> nextBookings() {
        Instant now = clock.instant();
        Map<Long, Party> byTable = new LinkedHashMap<>();
        // a booking a little late still holds its table until it becomes a no-show
        reservations.findByStatusAndTableIsNotNullAndStartsAtBetweenOrderByStartsAtAsc(ReservationStatus.BOOKED,
                        now.minus(Duration.ofHours(1)), now.plus(UPCOMING))
                .forEach(r -> byTable.putIfAbsent(r.getTable().getId(),
                        new Party(r.getId(), r.getGuestName(), r.getPartySize(), r.getStartsAt())));
        return byTable;
    }
}
