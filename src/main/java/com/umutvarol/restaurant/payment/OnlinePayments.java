package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import com.umutvarol.restaurant.payment.gateway.SimulatedGateway;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The online payment provider for the business at hand: its own account when it set one up (Sofra Cloud), else the
 * one this server is configured with, if any.
 */
public final class OnlinePayments {

    private final @Nullable OnlinePaymentGateway serverGateway;
    private final Supplier<Optional<OnlinePaymentGateway>> ownGateway;

    OnlinePayments(@Nullable OnlinePaymentGateway serverGateway) {
        this(serverGateway, Optional::empty);
    }

    OnlinePayments(@Nullable OnlinePaymentGateway serverGateway, Supplier<Optional<OnlinePaymentGateway>> ownGateway) {
        this.serverGateway = serverGateway;
        this.ownGateway = ownGateway;
    }

    public Optional<OnlinePaymentGateway> gateway() {
        return ownGateway.get().or(() -> Optional.ofNullable(serverGateway));
    }

    /** The server-wide provider, whatever a business set up for itself. */
    public Optional<OnlinePaymentGateway> serverGateway() {
        return Optional.ofNullable(serverGateway);
    }

    /** The simulated provider, when it is the active one; its checkout page is served by the app itself. */
    public Optional<SimulatedGateway> simulated() {
        return gateway().filter(SimulatedGateway.class::isInstance).map(SimulatedGateway.class::cast);
    }

    public boolean enabled() {
        return gateway().isPresent();
    }

    OnlinePaymentGateway require() {
        OnlinePaymentGateway gateway = gateway().orElse(null);
        if (gateway == null) {
            throw new BusinessRuleException("ONLINE_PAYMENTS_DISABLED",
                    "Online payments are not set up. Take the payment in cash or by card.");
        }
        return gateway;
    }
}
