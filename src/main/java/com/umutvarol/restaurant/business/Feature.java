package com.umutvarol.restaurant.business;

/** Parts of Sofra that the Starter plan leaves out (docs/SOFRA_CLOUD_PLAN.md). */
public enum Feature {
    ONLINE_PAYMENTS, DELIVERY_PLATFORMS, STOCK, FISCAL, SHIFTS_AND_TIPS,
    /** The Chain plan's own tools: each branch's prices and sales of the central menu, and the branches side by side. */
    CHAIN
}
