package com.umutvarol.restaurant.payment;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Payment settings.
 *
 * @param provider       who takes online (QR) payments. Left empty, demo mode uses {@code SIMULATED} and
 *                       production uses {@code NONE}
 * @param allowSimulated let the simulated provider run with demo mode off, e.g. on a staging server. Anyone
 *                       can "pay" with it, so never enable this on a live till
 * @param publicBaseUrl  the address guests reach the app on, e.g. {@code https://pay.sofra.example}. Used for
 *                       QR links and the provider's callback; taken from the request when empty
 * @param linkTtl        how long a QR payment link stays valid
 * @param pendingTtl     how long an online payment may stay unfinished before it is given up
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.payments")
public record PaymentProperties(
        @Nullable Provider provider,
        @DefaultValue("false") boolean allowSimulated,
        @Nullable String publicBaseUrl,
        @NotNull @DefaultValue("2h") Duration linkTtl,
        @NotNull @DefaultValue("30m") Duration pendingTtl) {

    public enum Provider {
        /** Online payments are switched off; cash and card at the table still work. */
        NONE,
        /** A stand-in for demos and tests: the guest approves or declines, nothing is charged. */
        SIMULATED,
        /** iyzico Checkout Form with 3-D Secure. */
        IYZICO
    }
}
