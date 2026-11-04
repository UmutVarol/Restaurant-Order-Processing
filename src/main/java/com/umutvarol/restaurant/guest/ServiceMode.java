package com.umutvarol.restaurant.guest;

/** Who takes the orders at the tables. Waiters can always enter an order at the till, whatever the mode. */
public enum ServiceMode {
    /** Waiters take the orders; a cart sent from a table waits until a waiter accepts it. */
    CLASSIC,
    /** Guests order from their phones; the service rules decide which carts a waiter checks first. */
    MIXED,
    /** Guests order from their phones; only dishes that always need a waiter, such as alcohol, wait. */
    QR_FIRST
}
