package com.umutvarol.restaurant.payment;

/** Cash and card refunds succeed when they are recorded; online refunds wait for the provider. */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
