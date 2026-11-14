package com.umutvarol.restaurant.staff;

import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.security.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;

/** Time actually worked: from clocking in to clocking out. A manager can correct it, with a reason. */
@Entity
@Table(name = "time_entries")
public class TimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** The user's id while open; the unique column that stops anyone being clocked in twice. */
    @Column(name = "open_user_id", unique = true)
    private Long openUserId;

    @Column(name = "clocked_in_at", nullable = false)
    private Instant clockedInAt;

    @Column(name = "clocked_out_at")
    private Instant clockedOutAt;

    @Column(name = "clocked_in_by", nullable = false, length = 50)
    private String clockedInBy;

    @Column(name = "clocked_out_by", length = 50)
    private String clockedOutBy;

    @Column(name = "corrected_by", length = 50)
    private String correctedBy;

    @Column(name = "correction_reason", length = 140)
    private String correctionReason;

    @Version
    private long version;

    protected TimeEntry() {
        // for JPA
    }

    TimeEntry(AppUser user, Instant at, String by) {
        this.user = user;
        this.openUserId = user.getId();
        this.clockedInAt = at;
        this.clockedInBy = by;
    }

    void clockOut(Instant at, String by) {
        if (clockedOutAt != null) {
            throw new InvalidStateException("NOT_CLOCKED_IN", "Not clocked in", Map.of());
        }
        // a clock that was set back, or a click in the same second: never a negative stretch
        this.clockedOutAt = at.isAfter(clockedInAt) ? at : clockedInAt.plusSeconds(1);
        this.clockedOutBy = by;
        this.openUserId = null;
    }

    /** A manager fixes a forgotten or wrong time; the reason stays on the entry. */
    void correct(Instant in, @Nullable Instant out, String reason, String by) {
        this.clockedInAt = in;
        this.clockedOutAt = out;
        this.openUserId = out == null ? user.getId() : null;
        if (out != null && clockedOutBy == null) {
            this.clockedOutBy = by;
        }
        this.correctedBy = by;
        this.correctionReason = reason;
    }

    /** The part of this entry inside [from, to), counting an open entry up to {@code now}. */
    Duration within(Instant from, Instant to, Instant now) {
        Instant end = clockedOutAt != null ? clockedOutAt : now;
        Instant start = clockedInAt.isAfter(from) ? clockedInAt : from;
        Instant stop = end.isBefore(to) ? end : to;
        return stop.isAfter(start) ? Duration.between(start, stop) : Duration.ZERO;
    }

    public boolean isOpen() {
        return clockedOutAt == null;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getClockedInAt() {
        return clockedInAt;
    }

    public @Nullable Instant getClockedOutAt() {
        return clockedOutAt;
    }

    public String getClockedInBy() {
        return clockedInBy;
    }

    public @Nullable String getClockedOutBy() {
        return clockedOutBy;
    }

    public @Nullable String getCorrectedBy() {
        return correctedBy;
    }

    public @Nullable String getCorrectionReason() {
        return correctionReason;
    }
}
