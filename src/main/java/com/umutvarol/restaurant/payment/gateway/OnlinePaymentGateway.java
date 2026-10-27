package com.umutvarol.restaurant.payment.gateway;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A card-payment provider with a hosted payment page: the guest enters card details and passes 3-D Secure on the
 * provider's page, so card numbers never reach Sofra (PCI DSS scope stays minimal). Implemented by
 * {@link IyzicoGateway} and, for demos and tests, {@link SimulatedGateway}.
 */
public interface OnlinePaymentGateway {

    /** Short provider name stored with each payment, e.g. {@code IYZICO}. */
    String provider();

    /** Starts a hosted checkout and returns where to send the guest. */
    CheckoutSession startCheckout(CheckoutRequest request);

    /**
     * Asks the provider how the checkout ended. Called when the guest comes back to the callback URL; the
     * callback itself is never trusted, only this server-to-server answer.
     */
    CheckoutOutcome retrieve(String token, String conversationId);

    /**
     * Refunds part or all of a successful payment.
     *
     * @return whether the provider accepted the refund
     * @throws PaymentGatewayException if the answer is unknown (provider unreachable); the refund is retried later
     */
    RefundOutcome refund(String providerPaymentId, BigDecimal amount, String conversationId);

    /**
     * @param conversationId our reference, echoed back by the provider (the payment id)
     * @param basketId       our reference for the bill
     * @param amount         what the guest pays, tip included
     * @param callbackUrl    where the provider sends the guest afterwards
     */
    record CheckoutRequest(String conversationId, String basketId, BigDecimal amount, String description,
                           String callbackUrl, String buyerName, @Nullable String buyerPhone, String buyerIp) {
    }

    record CheckoutSession(String token, String paymentPageUrl) {
    }

    record CheckoutOutcome(Status status, @Nullable String providerPaymentId, @Nullable BigDecimal paidAmount,
                           @Nullable String failureReason) {

        /**
         * {@code REVIEW}: the card was charged but the provider's fraud team is still checking it. The money is held
         * and the payment is neither confirmed nor given up until the provider decides.
         */
        public enum Status { SUCCEEDED, FAILED, PENDING, REVIEW }

        public static CheckoutOutcome pending() {
            return new CheckoutOutcome(Status.PENDING, null, null, null);
        }
    }

    record RefundOutcome(boolean succeeded, @Nullable String providerRefundId, @Nullable String failureReason) {
    }
}
