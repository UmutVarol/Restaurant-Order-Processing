package com.umutvarol.restaurant.order;

public enum OrderType {
    /** Served at a table in the restaurant; requires a table. */
    DINE_IN,
    /** Picked up at the counter by the guest. */
    TAKEAWAY,
    /** Delivered by a courier; requires an address and a phone number. */
    DELIVERY
}
