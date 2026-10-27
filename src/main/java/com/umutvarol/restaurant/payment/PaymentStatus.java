package com.umutvarol.restaurant.payment;

/**
 * Cash and card-terminal payments are recorded as {@link #SUCCEEDED} straight away. Online payments start
 * {@link #PENDING} while the guest is on the provider's page and end in one of the other states.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** The guest never finished paying online; the amount it held is released. */
    EXPIRED
}
