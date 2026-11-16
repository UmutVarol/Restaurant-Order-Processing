package com.umutvarol.restaurant.fiscal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.integration.IntegrationKind;
import com.umutvarol.restaurant.integration.IntegrationService;
import com.umutvarol.restaurant.integration.OutboundUrls;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Picks the device for each kind of document. A business that connected its own e-Arşiv integrator (Settings ›
 * Connections) uses it; otherwise the server's settings apply. On Sofra Cloud the server's own connections are never
 * used, so no business's documents are ever issued in another's name. The ÖKC is a device in the restaurant, on its
 * own network: a cloud server cannot reach it.
 */
@Component
class FiscalDevices {

    private static final Logger log = LoggerFactory.getLogger(FiscalDevices.class);

    private final Map<FiscalKind, FiscalDevice> devices = new EnumMap<>(FiscalKind.class);
    private final IntegrationService integrations;
    /** Each business's own e-Arşiv integrator, built once per saved version of its settings. */
    private final Cache<String, FiscalDevice> own = Caffeine.newBuilder().maximumSize(10_000).build();

    FiscalDevices(FiscalProperties properties, Clock clock, @Value("${restaurant.demo.enabled:true}") boolean demoMode,
                  IntegrationService integrations) {
        this.integrations = integrations;
        boolean simulate = demoMode || properties.simulateUnconfigured();
        connect(FiscalKind.RECEIPT, properties.okc(), "OKC", simulate, clock, true);
        connect(FiscalKind.E_ARSIV, properties.eArsiv(), "E_ARSIV", simulate, clock, false);
    }

    private void connect(FiscalKind kind, FiscalProperties.Connection connection, String name, boolean simulate, Clock clock,
                         boolean localOnly) {
        if (connection.configured() && integrations.cloud()) {
            log.warn("Fiscal {}: the server-wide connection is ignored on Sofra Cloud; each business connects its own.", kind);
        } else if (connection.configured()) {
            if (localOnly) {
                requireLocal(connection.baseUrl());
            }
            devices.put(kind, new HttpFiscalDevice(name, connection.baseUrl(), connection.apiKey(),
                    RestClient.builder().requestFactory(requestFactory())));
            log.info("Fiscal {}: connected to {}", kind, connection.baseUrl().getHost());
        } else if (simulate) {
            devices.put(kind, new SimulatedFiscalDevice(kind, clock));
            log.warn("Fiscal {}: simulated, nothing is declared to GİB.", kind);
        } else {
            log.warn("Fiscal {}: not set up; no legal documents of this kind are issued.", kind);
        }
    }

    Optional<FiscalDevice> device(FiscalKind kind) {
        if (kind == FiscalKind.E_ARSIV) {
            Optional<IntegrationService.Connection> mine = integrations.connection(IntegrationKind.E_ARSIV);
            if (mine.isPresent()) {
                return Optional.ofNullable(ownDevice(mine.get()));
            }
        }
        return Optional.ofNullable(devices.get(kind));
    }

    boolean simulated(FiscalKind kind) {
        return device(kind).map(SimulatedFiscalDevice.class::isInstance).orElse(false);
    }

    private @Nullable FiscalDevice ownDevice(IntegrationService.Connection connection) {
        String key = BusinessContext.current() + ":" + connection.version();
        FiscalDevice cached = own.getIfPresent(key);
        if (cached == null) {
            try {
                // checked again now: the name the business gave may point somewhere else than when it was saved
                URI base = OutboundUrls.requirePublicHttps(connection.baseUrl().toString());
                cached = new HttpFiscalDevice("E_ARSIV", base, connection.apiKey(), RestClient.builder().requestFactory(requestFactory()));
            } catch (DomainException refused) {
                log.warn("Fiscal E_ARSIV: the business's integrator address is refused ({})", refused.getCode());
                return null;
            }
            own.put(key, cached);
        }
        return cached;
    }

    /** The ÖKC bridge runs next to the till: refuse anything that is not on the local network. */
    private static void requireLocal(URI url) {
        try {
            InetAddress address = InetAddress.getByName(url.getHost());
            if (!(address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress())) {
                throw new IllegalStateException("The ÖKC bridge must be on the local network: " + url.getHost());
            }
        } catch (UnknownHostException ex) {
            throw new IllegalStateException("The ÖKC bridge host cannot be resolved: " + url.getHost(), ex);
        }
    }

    /** A device that hangs must not hold up closing bills: short connect and read timeouts. */
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }
}
