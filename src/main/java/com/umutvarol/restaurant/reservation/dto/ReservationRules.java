package com.umutvarol.restaurant.reservation.dto;

/** Shared input rules. */
public final class ReservationRules {

    /** Digits with the usual separators, an optional leading plus: enough to call back, nothing else. */
    public static final String PHONE = "^\\+?[0-9][0-9 ()-]{5,18}$";

    private ReservationRules() {
    }
}
