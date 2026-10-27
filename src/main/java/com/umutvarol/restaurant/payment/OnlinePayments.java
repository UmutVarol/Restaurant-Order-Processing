package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import com.umutvarol.restaurant.payment.gateway.SimulatedGateway;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The online payment provider this instance is configured with, if any. */
public final class OnlinePayments {

    private final @Nullable OnlinePaymentGateway gateway;

    OnlinePayments(@Nullable OnlinePaymentGateway gateway) {
        this.gateway = gateway;
    }

    public Optional<OnlinePaymentGateway> gateway() {
        return Optional.ofNullable(gateway);
    }

    /** The simulated provider, when it is the active one; its checkout page is served by the app itself. */
    public Optional<SimulatedGateway> simulated() {
        return gateway instanceof SimulatedGateway simulated ? Optional.of(simulated) : Optional.empty();
    }

    public boolean enabled() {
        return gateway != null;
    }

    OnlinePaymentGateway require() {
        if (gateway == null) {
            throw new BusinessRuleException("ONLINE_PAYMENTS_DISABLED",
                    "Online payments are not set up. Take the payment in cash or by card.");
        }
        return gateway;
    }
}
