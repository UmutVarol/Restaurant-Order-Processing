package com.umutvarol.restaurant.order;

/** What was changed on an order after it was placed. */
public enum AdjustmentKind {
    /** Units taken off a line: ordered by mistake, or the guest changed their mind. */
    VOID,
    /** Units made and served but not charged: "on the house" (ikram). */
    COMP,
    /** A manual discount on the whole order. */
    DISCOUNT,
    /** The order moved to another table with its seating. */
    MOVE,
    /** The order joined another table's bill. */
    MERGE,
    /** A held course was sent to the kitchen. */
    FIRE
}
