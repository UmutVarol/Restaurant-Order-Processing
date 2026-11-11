package com.umutvarol.restaurant.reservation;

/** How a party came to the restaurant. */
public enum ReservationKind {
    /** Booked ahead for a time. */
    BOOKING,
    /** Arrived without a booking and waits for a table. */
    WALK_IN
}
