package com.umutvarol.restaurant.business;

/**
 * What the business's plan allows (Sofra Cloud): the Starter plan has one branch, five active staff and fifteen
 * tables. Asked before adding one more; a restaurant's own server has no limits (no implementation, or an
 * unlimited plan).
 */
public interface UsageLimits {

    enum Resource { BRANCHES, STAFF, TABLES }

    /**
     * Allows adding one more {@code resource}, or refuses.
     *
     * @throws com.umutvarol.restaurant.common.error.SubscriptionRequiredException {@code PLAN_LIMIT} when the plan is full
     */
    void allowOneMore(Resource resource);
}
