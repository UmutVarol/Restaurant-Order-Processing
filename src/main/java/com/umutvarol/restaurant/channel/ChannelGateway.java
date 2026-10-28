package com.umutvarol.restaurant.channel;

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
 * Knows how each platform is connected and routes updates to the right adapter. A platform with a webhook secret is
 * connected; without one it is a simulation in demo mode and switched off otherwise.
 */
@Component
class ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelGateway.class);

    private final ChannelProperties properties;
    private final Map<Channel, ChannelMode> modes = new EnumMap<>(Channel.class);
    private final Map<Channel, ChannelAdapter> adapters = new EnumMap<>(Channel.class);
    private final SimulatedChannelAdapter simulated;

    ChannelGateway(ChannelProperties properties, Clock clock, @Value("${restaurant.demo.enabled:true}") boolean demoMode) {
        this.properties = properties;
        this.simulated = new SimulatedChannelAdapter(clock);
        for (Channel channel : Channel.values()) {
            ChannelProperties.Platform platform = properties.platform(channel);
            ChannelMode mode = platform.hasSecret() ? ChannelMode.CONNECTED
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

    ChannelMode mode(Channel channel) {
        return modes.get(channel);
    }

    /** The secret webhooks for this platform are signed with, or null when it takes none. */
    @Nullable String webhookSecret(Channel channel) {
        return mode(channel) == ChannelMode.CONNECTED ? properties.platform(channel).webhookSecret() : null;
    }

    Duration webhookTolerance() {
        return properties.webhookTolerance();
    }

    @Nullable ChannelAdapter adapter(Channel channel) {
        return adapters.get(channel);
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
