package com.umutvarol.restaurant.billing;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Subscriptions for Sofra Cloud ({@code restaurant.billing.*}). Off for a restaurant's own server: it pays for
 * itself. {@code SIMULATED} activates a plan without charging anything (development and the demo).
 *
 * @param prices the provider's price ids by {@code PLAN_INTERVAL}, e.g. {@code BUSINESS_MONTHLY}
 */
@ConfigurationProperties("restaurant.billing")
public record BillingProperties(
        @DefaultValue("NONE") Provider provider,
        @DefaultValue("14") int graceDays,
        String apiKey,
        String webhookSecret,
        @DefaultValue("https://sandbox-api.paddle.com") URI baseUrl,
        @DefaultValue Map<String, String> prices) {

    public enum Provider { NONE, SIMULATED, PADDLE }

    /** Keys never end up in logs or error pages, even if the settings object is printed. */
    @Override
    public String toString() {
        return "BillingProperties[provider=%s, apiKey=%s, webhookSecret=%s, baseUrl=%s]".formatted(provider,
                apiKey == null || apiKey.isBlank() ? "<not set>" : "****",
                webhookSecret == null || webhookSecret.isBlank() ? "<not set>" : "****", baseUrl);
    }
}
