package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.business.Business.Plan;
import java.util.Map;
import java.util.Optional;

/**
 * Charges nothing: the checkout activates the plan at once (the subscription service applies it when it sees the
 * simulated provider). For development, tests and the demo; never chosen on a server that bills real customers.
 */
class SimulatedBillingProvider implements BillingProvider {

    static final String NAME = "SIMULATED";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String checkout(CheckoutRequest request) {
        return request.returnUrl();
    }

    @Override
    public Optional<String> portal(String customerRef) {
        return Optional.empty();
    }

    @Override
    public void changeBranches(String subscriptionRef, Plan plan, BillingInterval interval, int branches) {
        // nothing is charged
    }

    @Override
    public Optional<SubscriptionUpdate> parseWebhook(byte[] body, Map<String, String> headers) {
        throw new InvalidWebhookException("The simulated provider sends no webhooks");
    }
}
