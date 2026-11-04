package com.umutvarol.restaurant.guest;

/** Why a cart from a table waits for a waiter instead of going straight to the kitchen. */
public enum HoldReason {
    /** The restaurant runs in {@link ServiceMode#CLASSIC}: every cart is checked. */
    CLASSIC_MODE,
    /** The cart has a dish the restaurant always confirms, such as alcohol. */
    NEEDS_APPROVAL,
    /** Nobody is seated at the table yet: a waiter confirms the guests are really there. */
    FIRST_CART,
    /** The cart is above the total the restaurant lets through on its own. */
    OVER_LIMIT,
    /** Several carts went straight to the kitchen from this table just now. */
    BURST
}
