package com.umutvarol.restaurant.reservation;

import java.util.Set;

/** Where a party is in its visit. */
public enum ReservationStatus {
    /** A booking that has not arrived yet. */
    BOOKED,
    /** A walk-in on the waitlist. */
    WAITING,
    /** At their table. */
    SEATED,
    /** Paid and left, or left without ordering. */
    FINISHED,
    CANCELLED,
    /** A booking that never came. */
    NO_SHOW;

    /** Parties that still hold, or will hold, a table. */
    public static final Set<ReservationStatus> ACTIVE = Set.of(BOOKED, WAITING, SEATED);

    public boolean isClosed() {
        return !ACTIVE.contains(this);
    }
}
