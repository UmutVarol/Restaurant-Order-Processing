package com.umutvarol.restaurant.compliance;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.fiscal.FiscalProperties;
import com.umutvarol.restaurant.integration.IntegrationKind;
import com.umutvarol.restaurant.integration.IntegrationService;
import com.umutvarol.restaurant.integration.OutboundUrls;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Whether a business keeps e-Adisyons, and where they go. Only a business GİB notified keeps them: it connects its own
 * integrator (Settings › Connections); a restaurant's own server may set one for itself in its environment. Without
 * either nothing is kept, unless the server is told to simulate them.
 */
@Component
class AdisyonSenders {

    private static final Logger log = LoggerFactory.getLogger(AdisyonSenders.class);

    private final IntegrationService integrations;
    private final @Nullable AdisyonSender server;
    private final Cache<String, AdisyonSender> own = Caffeine.newBuilder().maximumSize(10_000).build();

    AdisyonSenders(FiscalProperties properties, IntegrationService integrations, Clock clock) {
        this.integrations = integrations;
        FiscalProperties.Connection connection = properties.eAdisyon();
        if (connection.configured() && !integrations.cloud()) {
            server = new HttpAdisyonSender(connection.baseUrl(), connection.apiKey(), RestClient.builder().requestFactory(requestFactory()));
            log.info("e-Adisyon: connected to {}", connection.baseUrl().getHost());
        } else if (properties.simulateEAdisyon()) {
            server = new SimulatedAdisyonSender(clock);
            log.warn("e-Adisyon: simulated, nothing is sent to GİB.");
        } else {
            server = null;
        }
    }

    /** The current business's integrator, if it keeps e-Adisyons. */
    Optional<AdisyonSender> sender() {
        Optional<IntegrationService.Connection> mine = integrations.connection(IntegrationKind.E_ADISYON);
        if (mine.isPresent() && mine.get().hasApi()) {
            return Optional.ofNullable(ownSender(mine.get()));
        }
        return integrations.cloud() && server instanceof HttpAdisyonSender ? Optional.empty() : Optional.ofNullable(server);
    }

    private @Nullable AdisyonSender ownSender(IntegrationService.Connection connection) {
        String key = BusinessContext.current() + ":" + connection.version();
        AdisyonSender cached = own.getIfPresent(key);
        if (cached == null) {
            try {
                // checked again now: the name the business gave may point somewhere else than when it was saved
                URI base = OutboundUrls.requirePublicHttps(connection.baseUrl().toString());
                cached = new HttpAdisyonSender(base, connection.apiKey(), RestClient.builder().requestFactory(requestFactory()));
            } catch (DomainException refused) {
                log.warn("e-Adisyon: the business's integrator address is refused ({})", refused.getCode());
                return null;
            }
            own.put(key, cached);
        }
        return cached;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }
}
