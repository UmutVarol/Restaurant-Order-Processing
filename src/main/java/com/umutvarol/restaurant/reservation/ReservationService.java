package com.umutvarol.restaurant.reservation;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.floor.TableChangedEvent;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.reservation.dto.BookingRequest;
import com.umutvarol.restaurant.reservation.dto.ReservationResponse;
import com.umutvarol.restaurant.reservation.dto.SeatRequest;
import com.umutvarol.restaurant.reservation.dto.TableOption;
import com.umutvarol.restaurant.reservation.dto.WalkInRequest;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bookings and the walk-in waitlist. A table is never given to two parties for overlapping times: every change
 * that touches a table locks its row first, then checks the other parties on it. Seating a party at a table that
 * someone else has booked soon is refused unless the host overrides it knowingly. Bookings that do not arrive
 * become no-shows after a grace period; seated parties finish when their last order is paid. Phone numbers are
 * erased some time after the visit.
 */
@Service
@Transactional
public class ReservationService {

    /** Who closes parties on their own, in the audit log. */
    static final String SYSTEM = "system";
    /** How far ahead a booking can be taken. */
    static final Duration BOOKING_HORIZON = Duration.ofDays(180);
    /** A booking can be entered a little after its time, for a party standing at the door. */
    static final Duration LATE_ENTRY = Duration.ofMinutes(15);

    private static final Logger audit = LoggerFactory.getLogger("reservation.audit");
    private static final Set<ReservationStatus> HOLDING_TABLE = Set.of(ReservationStatus.BOOKED, ReservationStatus.SEATED);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservationRepository reservations;
    private final DiningTableRepository tables;
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;
    private final Duration defaultDuration;
    private final Duration noShowAfter;
    private final Duration keepContactFor;

    public ReservationService(ReservationRepository reservations, DiningTableRepository tables, OrderRepository orders,
                              ApplicationEventPublisher events, Clock clock, RestaurantProperties restaurant,
                              @Value("${restaurant.reservations.default-duration:PT90M}") Duration defaultDuration,
                              @Value("${restaurant.reservations.no-show-after:PT20M}") Duration noShowAfter,
                              @Value("${restaurant.reservations.keep-contact-for:P30D}") Duration keepContactFor) {
        this.reservations = reservations;
        this.tables = tables;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
        this.zone = restaurant.timeZone();
        this.defaultDuration = defaultDuration;
        this.noShowAfter = noShowAfter;
        this.keepContactFor = keepContactFor;
    }

    /** The day's bookings (in the restaurant's time zone), the waitlist and the seated parties. */
    @Transactional(readOnly = true)
    public List<ReservationResponse> day(@Nullable LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(zone));
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        return reservations.forDay(from, to).stream().map(ReservationService::toResponse).toList();
    }

    public ReservationResponse book(BookingRequest request, String actor) {
        Instant now = clock.instant();
        if (request.startsAt().isBefore(now.minus(LATE_ENTRY))) {
            throw new BusinessRuleException("RESERVATION_IN_PAST", "A booking cannot start in the past");
        }
        if (request.startsAt().isAfter(now.plus(BOOKING_HORIZON))) {
            throw new BusinessRuleException("RESERVATION_TOO_FAR", "Bookings are taken up to 180 days ahead");
        }
        int minutes = duration(request.durationMinutes());
        DiningTable table = request.tableId() == null ? null
                : checkedTable(request.tableId(), request.partySize(), request.startsAt(), minutes, null);
        Reservation booking = reservations.save(Reservation.booking(request.guestName().strip(), phone(request.phone()),
                request.partySize(), request.startsAt(), minutes, table, text(request.note()), now, actor));
        audit.info("reservation.booked id={} party={} at={} table={} by={}", booking.getId(), booking.getPartySize(),
                booking.getStartsAt(), code(table), actor);
        return changed(booking, now);
    }

    public ReservationResponse update(Long id, BookingRequest request, String actor) {
        Reservation reservation = find(id);
        Instant now = clock.instant();
        int minutes = duration(request.durationMinutes());
        DiningTable table = null;
        if (reservation.getStatus() == ReservationStatus.BOOKED) {
            if (!request.startsAt().equals(reservation.getStartsAt()) && request.startsAt().isBefore(now.minus(LATE_ENTRY))) {
                throw new BusinessRuleException("RESERVATION_IN_PAST", "A booking cannot start in the past");
            }
            table = request.tableId() == null ? null
                    : checkedTable(request.tableId(), request.partySize(), request.startsAt(), minutes, reservation.getId());
        }
        reservation.reschedule(request.guestName().strip(), phone(request.phone()), request.partySize(),
                request.startsAt(), minutes, table, text(request.note()));
        audit.info("reservation.changed id={} party={} at={} table={} by={}", id, reservation.getPartySize(),
                reservation.getStartsAt(), code(reservation.getTable()), actor);
        return changed(reservation, now);
    }

    public ReservationResponse addWalkIn(WalkInRequest request, String actor) {
        Instant now = clock.instant();
        Reservation walkIn = reservations.save(Reservation.walkIn(request.guestName().strip(), phone(request.phone()),
                request.partySize(), duration(null), request.quotedMinutes(), text(request.note()), now, actor));
        audit.info("reservation.waitlisted id={} party={} quoted={} by={}", walkIn.getId(), walkIn.getPartySize(),
                walkIn.getQuotedMinutes(), actor);
        return changed(walkIn, now);
    }

    public ReservationResponse seat(Long id, SeatRequest request, String actor) {
        Reservation party = find(id);
        Instant now = clock.instant();
        DiningTable table = lockTable(request.tableId());
        if (party.getPartySize() > table.getSeats()) {
            throw tooSmall(table, party.getPartySize());
        }
        boolean otherPartySeated = reservations.findByTableIdAndStatusIn(table.getId(), Set.of(ReservationStatus.SEATED))
                .stream().anyMatch(other -> !other.getId().equals(id));
        if (otherPartySeated || orders.existsByTableIdAndStatusIn(table.getId(), OrderStatus.activeStatuses())) {
            throw new InvalidStateException("TABLE_OCCUPIED", "Table %s is in use".formatted(table.getCode()),
                    Map.of("table", table.getCode()));
        }
        Instant ends = now.plus(Duration.ofMinutes(party.getDurationMinutes()));
        Reservation booked = reservations.findByTableIdAndStatusIn(table.getId(), Set.of(ReservationStatus.BOOKED)).stream()
                .filter(other -> !other.getId().equals(id))
                .filter(other -> other.getStartsAt().isBefore(ends) && other.endsAt().isAfter(now))
                .min(Comparator.comparing(Reservation::getStartsAt))
                .orElse(null);
        if (booked != null && !request.overrides()) {
            String time = TIME.format(booked.getStartsAt().atZone(zone));
            throw new InvalidStateException("TABLE_BOOKED_SOON",
                    "Table %s is booked for %s at %s".formatted(table.getCode(), booked.getGuestName(), time),
                    Map.of("table", table.getCode(), "guest", booked.getGuestName(), "time", time));
        }
        party.seat(table, now);
        // new guests at a table that still waited to be cleared: it was cleared for them
        if (table.cleared()) {
            events.publishEvent(new TableChangedEvent(table.getId(), table.getCode(), null, now));
        }
        audit.info("reservation.seated id={} table={} party={} override={} by={}", id, table.getCode(),
                party.getPartySize(), booked != null, actor);
        return changed(party, now);
    }

    public ReservationResponse cancel(Long id, String actor) {
        Reservation reservation = find(id);
        Instant now = clock.instant();
        reservation.cancel(now, actor);
        audit.info("reservation.cancelled id={} by={}", id, actor);
        return changed(reservation, now);
    }

    public ReservationResponse noShow(Long id, String actor) {
        Reservation reservation = find(id);
        Instant now = clock.instant();
        reservation.noShow(now, actor);
        audit.info("reservation.no_show id={} by={}", id, actor);
        return changed(reservation, now);
    }

    /** The party left: after paying, or without ordering. */
    public ReservationResponse finish(Long id, String actor) {
        Reservation reservation = find(id);
        Instant now = clock.instant();
        reservation.finish(now, actor);
        audit.info("reservation.finished id={} by={}", id, actor);
        return changed(reservation, now);
    }

    /** Tables with enough seats and no other party for the whole visit; now-occupied tables too when it starts soon. */
    @Transactional(readOnly = true)
    public List<TableOption> freeTables(Instant startsAt, int partySize, @Nullable Integer durationMinutes,
                                        @Nullable Long exceptReservation) {
        Instant ends = startsAt.plus(Duration.ofMinutes(duration(durationMinutes)));
        boolean soon = startsAt.isBefore(clock.instant().plus(Duration.ofMinutes(30)));
        return tables.findAllByOrderByIdAsc().stream()
                .filter(table -> table.getSeats() >= partySize)
                .filter(table -> conflicts(table.getId(), startsAt, ends, exceptReservation).isEmpty())
                .filter(table -> !soon || !orders.existsByTableIdAndStatusIn(table.getId(), OrderStatus.activeStatuses()))
                .map(table -> new TableOption(table.getId(), table.getCode(), table.getSeats(), table.getZone()))
                .toList();
    }

    /** A seated party is done once the last order at their table is paid. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderChanged(OrderChangedEvent event) {
        if (event.orderType() != OrderType.DINE_IN || event.tableCode() == null || event.status() != OrderStatus.COMPLETED) {
            return;
        }
        tables.findByCode(event.tableCode())
                .filter(table -> !orders.existsByTableIdAndStatusIn(table.getId(), OrderStatus.activeStatuses()))
                .ifPresent(table -> reservations.findByTableIdAndStatusIn(table.getId(), Set.of(ReservationStatus.SEATED))
                        .forEach(party -> {
                            party.finish(event.occurredAt(), SYSTEM);
                            audit.info("reservation.finished id={} table={} by={}", party.getId(), table.getCode(), SYSTEM);
                            changed(party, event.occurredAt());
                        }));
    }

    /** Bookings that have not arrived some time after their start free their table. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void markNoShows() {
        Instant now = clock.instant();
        reservations.findByStatusAndStartsAtBefore(ReservationStatus.BOOKED, now.minus(noShowAfter)).forEach(booking -> {
            booking.noShow(now, SYSTEM);
            audit.info("reservation.no_show id={} at={} by={}", booking.getId(), booking.getStartsAt(), SYSTEM);
            changed(booking, now);
        });
    }

    /** Phone numbers are needed only around the visit (KVKK: kept no longer than the purpose needs). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public int eraseOldContacts() {
        Instant before = clock.instant().minus(keepContactFor);
        Set<ReservationStatus> closed = Set.of(ReservationStatus.FINISHED, ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW);
        int erased = (int) reservations.findByPhoneIsNotNullAndStatusInAndStartsAtBefore(closed, before).stream()
                .filter(Reservation::eraseContact)
                .count();
        if (erased > 0) {
            audit.info("reservation.contacts_erased count={} before={}", erased, before);
        }
        return erased;
    }

    /** Locks the table, then checks it fits the party and nobody else holds it for that time. */
    private DiningTable checkedTable(Long tableId, int partySize, Instant startsAt, int minutes, @Nullable Long self) {
        DiningTable table = lockTable(tableId);
        if (partySize > table.getSeats()) {
            throw tooSmall(table, partySize);
        }
        Instant ends = startsAt.plus(Duration.ofMinutes(minutes));
        conflicts(table.getId(), startsAt, ends, self).stream().findFirst().ifPresent(other -> {
            String time = TIME.format(other.getStartsAt().atZone(zone));
            throw new InvalidStateException("TABLE_ALREADY_BOOKED",
                    "Table %s is already taken by %s at %s".formatted(table.getCode(), other.getGuestName(), time),
                    Map.of("table", table.getCode(), "guest", other.getGuestName(), "time", time));
        });
        return table;
    }

    private List<Reservation> conflicts(Long tableId, Instant startsAt, Instant endsAt, @Nullable Long self) {
        return reservations.findByTableIdAndStatusIn(tableId, HOLDING_TABLE).stream()
                .filter(other -> !other.getId().equals(self))
                .filter(other -> other.getStartsAt().isBefore(endsAt) && other.endsAt().isAfter(startsAt))
                .sorted(Comparator.comparing(Reservation::getStartsAt))
                .toList();
    }

    private DiningTable lockTable(Long tableId) {
        return tables.lockById(tableId).orElseThrow(() -> new ResourceNotFoundException("table", tableId));
    }

    private static BusinessRuleException tooSmall(DiningTable table, int partySize) {
        return new BusinessRuleException("TABLE_TOO_SMALL",
                "Table %s seats %d, the party is %d".formatted(table.getCode(), table.getSeats(), partySize),
                Map.of("table", table.getCode(), "seats", table.getSeats(), "party", partySize));
    }

    private Reservation find(Long id) {
        return reservations.findById(id).orElseThrow(() -> new ResourceNotFoundException("reservation", id));
    }

    private int duration(@Nullable Integer minutes) {
        return minutes != null ? minutes : (int) defaultDuration.toMinutes();
    }

    private ReservationResponse changed(Reservation reservation, Instant now) {
        DiningTable table = reservation.getTable();
        events.publishEvent(new ReservationChangedEvent(reservation.getId(), reservation.getStatus(),
                table == null ? null : table.getId(), now));
        return toResponse(reservation);
    }

    private static @Nullable String phone(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static @Nullable String text(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static @Nullable String code(@Nullable DiningTable table) {
        return table == null ? null : table.getCode();
    }

    static ReservationResponse toResponse(Reservation r) {
        DiningTable table = r.getTable();
        return new ReservationResponse(r.getId(), r.getKind(), r.getStatus(), r.getGuestName(), r.getPhone(),
                r.getPartySize(), r.getStartsAt(), r.endsAt(), r.getDurationMinutes(), r.getQuotedMinutes(),
                table == null ? null : table.getId(), code(table), r.getNote(), r.getCreatedAt(), r.getCreatedBy(),
                r.getSeatedAt(), r.getClosedAt());
    }
}
