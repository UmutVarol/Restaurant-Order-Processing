package com.umutvarol.restaurant.guest;

public enum GuestRequestStatus {
    /** Waiting for staff. */
    OPEN,
    /** An order request that became a real order. */
    ACCEPTED,
    /** An order request staff turned down, with a reason the guest sees. */
    REJECTED,
    /** A call staff attended to. */
    DONE
}
