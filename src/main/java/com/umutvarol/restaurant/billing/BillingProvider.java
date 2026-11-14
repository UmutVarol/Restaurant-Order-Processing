package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.business.Business.Plan;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Who collects the subscription: Paddle (in dollars, as merchant of record) or a simulation for development and the
 * demo. Sofra never sees card data: the provider's own checkout page takes it.
 */
public interface BillingProvider {

    String name();

    /** A page of the provider where the owner pays for {@code plan} for {@code branches} branches. */
    String checkout(CheckoutRequest request);

    /** The provider's page where the owner changes their card, sees invoices or cancels, if it has one. */
    Optional<String> portal(String customerRef);

    /** Bills a different number of branches from now on (a branch opened or closed), prorated. */
    void changeBranches(String subscriptionRef, Plan plan, BillingInterval interval, int branches);

    /**
     * Checks that a webhook really comes from the provider and says what it means.
     *
     * @throws InvalidWebhookException when the signature does not hold or the body cannot be read
     */
    Optional<SubscriptionUpdate> parseWebhook(byte[] body, Map<String, String> headers);

    record CheckoutRequest(long businessId, String businessName, String email, Plan plan, BillingInterval interval,
                           int branches, @Nullable String customerRef, String returnUrl) {
    }

    /** What happened to a subscription, in Sofra's words. */
    record SubscriptionUpdate(long businessId, String customerRef, String subscriptionRef, Plan plan,
                              BillingInterval interval, int branches, State state, @Nullable Instant periodEndsAt,
                              @Nullable Instant occurredAt) {

        public enum State { ACTIVE, PAST_DUE, CANCELLED }
    }

    final class InvalidWebhookException extends RuntimeException {
        public InvalidWebhookException(String message) {
            super(message);
        }
    }
}
