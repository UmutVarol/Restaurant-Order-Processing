package com.umutvarol.restaurant.security;

/** Staff roles. Each maps to a Spring Security authority {@code ROLE_<name>}. */
public enum Role {
    /** Manager: everything, including menu management and reports. */
    ADMIN,
    /** Floor staff: takes and cancels orders, serves them. */
    WAITER,
    /** Kitchen staff: moves orders through preparation. */
    KITCHEN
}
