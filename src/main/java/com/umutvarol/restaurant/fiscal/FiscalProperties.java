package com.umutvarol.restaurant.fiscal;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where legal documents are issued. Credentials come from the environment only. Without a connection a kind of
 * document is simulated in demo mode and not issued at all otherwise (the cashier screen says so).
 *
 * @param okc                  the ÖKC vendor's bridge on the till's own network
 * @param eArsiv               the e-Arşiv integrator
 * @param simulateUnconfigured simulate what is not connected, as in demo mode
 * @param eAdisyon             the e-Adisyon integrator, for a restaurant GİB notified (VUK 509)
 * @param simulateEAdisyon     keep simulated e-Adisyons without an integrator; off by default, since only businesses
 *                             GİB notified keep them
 */
@ConfigurationProperties(prefix = "restaurant.fiscal")
public record FiscalProperties(
        @DefaultValue Connection okc,
        @DefaultValue Connection eArsiv,
        @DefaultValue("false") boolean simulateUnconfigured,
        @DefaultValue Connection eAdisyon,
        @DefaultValue("false") boolean simulateEAdisyon) {

    public record Connection(@Nullable URI baseUrl, @Nullable String apiKey) {

        public boolean configured() {
            return baseUrl != null && apiKey != null && !apiKey.isBlank();
        }
    }
}
