package com.umutvarol.restaurant.payment;

/** A bill is open while its orders are in progress or money is still due. */
public enum BillStatus {
    OPEN,
    /** Every order finished and the bill was settled. */
    CLOSED,
    /** Every order was cancelled and nothing was kept. */
    VOID
}
