package com.umutvarol.restaurant.guest;

/** What a guest asks for from their table. */
public enum GuestRequestKind {
    /** A cart for a waiter to accept into a real order. */
    ORDER,
    CALL_WAITER,
    REQUEST_BILL
}
