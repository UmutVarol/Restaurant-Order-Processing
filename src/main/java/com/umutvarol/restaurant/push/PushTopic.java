package com.umutvarol.restaurant.push;

/** What a device can ask to be told of. */
public enum PushTopic {
    /** An order reached the kitchen (the till, a table's QR code or a delivery platform). */
    NEW_ORDER,
    /** The kitchen has an order ready to be carried or picked up. */
    READY,
    /** A guest called the waiter, asked for the bill or sent a cart from the table. */
    GUEST_CALL
}
