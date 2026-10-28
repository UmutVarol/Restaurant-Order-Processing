package com.umutvarol.restaurant.channel;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Delivery platform connections. Credentials come only from the environment, never from the database or the API.
 *
 * @param webhookTolerance     how far a webhook's timestamp may be from our clock; older deliveries are refused as
 *                             replays
 * @param simulateUnconfigured platforms without a webhook secret run as simulations (demo mode): orders can be
 *                             generated from the Channels screen and status updates are only recorded
 */
@ConfigurationProperties(prefix = "restaurant.channels")
public record ChannelProperties(
        @DefaultValue("5m") Duration webhookTolerance,
        @DefaultValue("false") boolean simulateUnconfigured,
        @DefaultValue Platform yemeksepeti,
        @DefaultValue Platform getir,
        @DefaultValue Platform trendyol) {

    /**
     * @param webhookSecret signs the orders the platform (or the integrator in front of it) sends us
     * @param apiBaseUrl    where status and availability updates go; empty to keep them on our side only
     * @param apiToken      bearer token for {@code apiBaseUrl}
     */
    public record Platform(@Nullable String webhookSecret, @Nullable URI apiBaseUrl, @Nullable String apiToken) {

        boolean hasSecret() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }

        boolean hasApi() {
            return apiBaseUrl != null && apiToken != null && !apiToken.isBlank();
        }
    }

    Platform platform(Channel channel) {
        return switch (channel) {
            case YEMEKSEPETI -> yemeksepeti;
            case GETIR -> getir;
            case TRENDYOL -> trendyol;
        };
    }
}
