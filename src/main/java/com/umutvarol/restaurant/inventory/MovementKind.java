package com.umutvarol.restaurant.inventory;

/** Why stock moved. Only the ledger itself records sales; people record the rest. */
public enum MovementKind {
    /** Used by a completed order, from its dishes' recipes. */
    SALE,
    /** Thrown away, with a reason. */
    WASTE,
    /** Bought in from a supplier. */
    DELIVERY,
    /** The difference a stock count found between the shelf and the ledger. */
    COUNT
}
