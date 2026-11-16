package com.umutvarol.restaurant.channel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.integration.IntegrationKind;
import com.umutvarol.restaurant.integration.IntegrationService;
import com.umutvarol.restaurant.integration.OutboundUrls;
import java.net.URI;
import java.util.Optional;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Knows how each platform is connected and routes updates to the right adapter. A business that connected a
 * platform itself (Settings › Connections) uses its own secret and API; otherwise the server's settings apply: a
 * platform with a webhook secret is connected, without one it is a simulation in demo mode and switched off
 * otherwise. On Sofra Cloud the server's own platform settings are never shared between businesses.
 */
@Component
class ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelGateway.class);

    private final ChannelProperties properties;
    private final Map<Channel, ChannelMode> modes = new EnumMap<>(Channel.class);
    private final Map<Channel, ChannelAdapter> adapters = new EnumMap<>(Channel.class);
    private final SimulatedChannelAdapter simulated;
    private final IntegrationService integrations;
    /** Each business's own API adapter, built once per saved version of its settings. */
    private final Cache<String, ChannelAdapter> own = Caffeine.newBuilder().maximumSize(10_000).build();

    ChannelGateway(ChannelProperties properties, Clock clock, @Value("${restaurant.demo.enabled:true}") boolean demoMode,
                   IntegrationService integrations) {
        this.properties = properties;
        this.simulated = new SimulatedChannelAdapter(clock);
        this.integrations = integrations;
        for (Channel channel : Channel.values()) {
            ChannelProperties.Platform platform = properties.platform(channel);
            ChannelMode mode = platform.hasSecret() && !integrations.cloud() ? ChannelMode.CONNECTED
                    : demoMode || properties.simulateUnconfigured() ? ChannelMode.SIMULATED : ChannelMode.OFF;
            modes.put(channel, mode);
            if (mode == ChannelMode.CONNECTED && platform.hasApi()) {
                adapters.put(channel, new HttpChannelAdapter(platform.apiBaseUrl(), platform.apiToken(),
                        RestClient.builder().requestFactory(requestFactory())));
            } else if (mode != ChannelMode.OFF) {
                // a connected platform without an API gets its updates recorded, like the simulation
                adapters.put(channel, simulated);
            }
            if (mode != ChannelMode.OFF) {
                log.info("Delivery channel {}: {}{}", channel, mode, mode == ChannelMode.CONNECTED && !platform.hasApi()
                        ? " (webhook only; updates are not sent anywhere)" : "");
            }
        }
    }

    private Optional<IntegrationService.Connection> ownConnection(Channel channel) {
        return integrations.connection(IntegrationKind.valueOf(channel.name()));
    }

    ChannelMode mode(Channel channel) {
        return ownConnection(channel).isPresent() ? ChannelMode.CONNECTED : modes.get(channel);
    }

    /** The secret webhooks for this platform are signed with, or null when it takes none. */
    @Nullable String webhookSecret(Channel channel) {
        Optional<IntegrationService.Connection> mine = ownConnection(channel);
        if (mine.isPresent()) {
            return mine.get().webhookSecret();
        }
        return modes.get(channel) == ChannelMode.CONNECTED ? properties.platform(channel).webhookSecret() : null;
    }

    /** Only the business's own secret: a webhook addressed to one business never falls back to the server's. */
    @Nullable String ownWebhookSecret(Channel channel) {
        return ownConnection(channel).map(IntegrationService.Connection::webhookSecret).orElse(null);
    }

    Duration webhookTolerance() {
        return properties.webhookTolerance();
    }

    @Nullable ChannelAdapter adapter(Channel channel) {
        Optional<IntegrationService.Connection> mine = ownConnection(channel);
        if (mine.isEmpty()) {
            return adapters.get(channel);
        }
        IntegrationService.Connection connection = mine.get();
        if (!connection.hasApi()) {
            return simulated; // webhook only: updates are recorded, not sent
        }
        String key = BusinessContext.current() + ":" + channel + ":" + connection.version();
        ChannelAdapter cached = own.getIfPresent(key);
        if (cached == null) {
            try {
                // checked again now: the name the business gave may point somewhere else than when it was saved
                URI base = OutboundUrls.requirePublicHttps(connection.baseUrl().toString());
                cached = new HttpChannelAdapter(base, connection.apiKey(), RestClient.builder().requestFactory(requestFactory()));
            } catch (DomainException refused) {
                log.warn("Delivery channel {}: the business's API address is refused ({})", channel, refused.getCode());
                return simulated;
            }
            own.put(key, cached);
        }
        return cached;
    }

    List<SimulatedChannelAdapter.Sent> recentUpdates(Channel channel) {
        return simulated.recent(channel);
    }

    /** A platform that hangs must not hold anything up: short connect and read timeouts. */
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
