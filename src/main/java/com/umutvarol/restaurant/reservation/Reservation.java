package com.umutvarol.restaurant.reservation;

import org.hibernate.annotations.Filter;
import jakarta.persistence.EntityListeners;
import com.umutvarol.restaurant.business.BranchStamp;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.table.DiningTable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * A party that will sit at a table: a booking for a time, or a walk-in waiting for the next free table. A booking
 * may name its table ahead; a walk-in gets one when seated.
 */
@Entity
@Table(name = "reservations")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class Reservation implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** The branch this happened in (BranchOwned); lists are limited to it when a request works in one branch. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @Column(name = "guest_name", nullable = false, length = 60)
    private String guestName;

    @Column(length = 20)
    private String phone;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "quoted_minutes")
    private Integer quotedMinutes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id")
    private DiningTable table;

    @Column(length = 140)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "seated_at")
    private Instant seatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Version
    private long version;

    protected Reservation() {
        // for JPA
    }

    private Reservation(ReservationKind kind, ReservationStatus status, String guestName, @Nullable String phone,
                        int partySize, Instant startsAt, int durationMinutes, @Nullable String note, Instant now,
                        String actor) {
        this.kind = kind;
        this.status = status;
        this.guestName = guestName;
        this.phone = phone;
        this.partySize = partySize;
        this.startsAt = startsAt;
        this.durationMinutes = durationMinutes;
        this.note = note;
        this.createdAt = now;
        this.createdBy = actor;
    }

    static Reservation booking(String guestName, @Nullable String phone, int partySize, Instant startsAt,
                               int durationMinutes, @Nullable DiningTable table, @Nullable String note, Instant now,
                               String actor) {
        Reservation booking = new Reservation(ReservationKind.BOOKING, ReservationStatus.BOOKED, guestName, phone,
                partySize, startsAt, durationMinutes, note, now, actor);
        booking.table = table;
        return booking;
    }

    static Reservation walkIn(String guestName, @Nullable String phone, int partySize, int durationMinutes,
                              @Nullable Integer quotedMinutes, @Nullable String note, Instant now, String actor) {
        Reservation walkIn = new Reservation(ReservationKind.WALK_IN, ReservationStatus.WAITING, guestName, phone,
                partySize, now, durationMinutes, note, now, actor);
        walkIn.quotedMinutes = quotedMinutes;
        return walkIn;
    }

    /** Changes a booking that has not arrived yet. */
    void reschedule(String guestName, @Nullable String phone, int partySize, Instant startsAt, int durationMinutes,
                    @Nullable DiningTable table, @Nullable String note) {
        require(Set.of(ReservationStatus.BOOKED, ReservationStatus.WAITING), "change");
        this.guestName = guestName;
        this.phone = phone;
        this.partySize = partySize;
        this.durationMinutes = durationMinutes;
        this.note = note;
        if (status == ReservationStatus.BOOKED) {
            this.startsAt = startsAt;
            this.table = table;
        }
    }

    void seat(DiningTable at, Instant now) {
        require(Set.of(ReservationStatus.BOOKED, ReservationStatus.WAITING), "seat");
        this.table = at;
        this.status = ReservationStatus.SEATED;
        this.seatedAt = now;
        if (kind == ReservationKind.WALK_IN) {
            // a walk-in's visit starts when they sit down; it is what conflicts with later bookings
            this.startsAt = now;
        }
    }

    void cancel(Instant now, String actor) {
        require(Set.of(ReservationStatus.BOOKED, ReservationStatus.WAITING), "cancel");
        close(ReservationStatus.CANCELLED, now, actor);
    }

    void noShow(Instant now, String actor) {
        require(Set.of(ReservationStatus.BOOKED), "mark as no-show");
        close(ReservationStatus.NO_SHOW, now, actor);
    }

    void finish(Instant now, String actor) {
        require(Set.of(ReservationStatus.SEATED), "finish");
        close(ReservationStatus.FINISHED, now, actor);
    }

    /** Personal data is kept only as long as the visit needs it. */
    boolean eraseContact() {
        boolean had = phone != null;
        this.phone = null;
        return had;
    }

    private void close(ReservationStatus to, Instant now, String actor) {
        this.status = to;
        this.closedAt = now;
        this.closedBy = actor;
    }

    private void require(Set<ReservationStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new InvalidStateException("RESERVATION_" + status.name(),
                    "Cannot %s a reservation that is %s".formatted(action, status.name().toLowerCase().replace('_', '-')),
                    Map.of("status", status.name()));
        }
    }

    /** When the party is expected to leave the table. */
    public Instant endsAt() {
        Instant from = seatedAt != null && seatedAt.isAfter(startsAt) ? seatedAt : startsAt;
        return from.plus(Duration.ofMinutes(durationMinutes));
    }

    public Long getId() {
        return id;
    }

    public ReservationKind getKind() {
        return kind;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getGuestName() {
        return guestName;
    }

    public @Nullable String getPhone() {
        return phone;
    }

    public int getPartySize() {
        return partySize;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public @Nullable Integer getQuotedMinutes() {
        return quotedMinutes;
    }

    public @Nullable DiningTable getTable() {
        return table;
    }

    public @Nullable String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public @Nullable Instant getSeatedAt() {
        return seatedAt;
    }

    public @Nullable Instant getClosedAt() {
        return closedAt;
    }

    @Override
    public @Nullable Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }

    @Override
    public @Nullable Long branchHint() {
        return table == null ? null : table.getBranchId();
    }
}
