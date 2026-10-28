package com.umutvarol.restaurant.fiscal;

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

/** Picks the device for each kind of document from the settings. */
@Component
class FiscalDevices {

    private static final Logger log = LoggerFactory.getLogger(FiscalDevices.class);

    private final Map<FiscalKind, FiscalDevice> devices = new EnumMap<>(FiscalKind.class);

    FiscalDevices(FiscalProperties properties, Clock clock, @Value("${restaurant.demo.enabled:true}") boolean demoMode) {
        boolean simulate = demoMode || properties.simulateUnconfigured();
        connect(FiscalKind.RECEIPT, properties.okc(), "OKC", simulate, clock, true);
        connect(FiscalKind.E_ARSIV, properties.eArsiv(), "E_ARSIV", simulate, clock, false);
    }

    private void connect(FiscalKind kind, FiscalProperties.Connection connection, String name, boolean simulate, Clock clock,
                         boolean localOnly) {
        if (connection.configured()) {
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
        return Optional.ofNullable(devices.get(kind));
    }

    boolean simulated(FiscalKind kind) {
        return devices.get(kind) instanceof SimulatedFiscalDevice;
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
