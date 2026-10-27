package com.umutvarol.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.payment.PaymentProperties.Provider;
import com.umutvarol.restaurant.payment.gateway.IyzicoProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentGatewayConfigTest {

    private static final IyzicoProperties NO_KEYS = new IyzicoProperties(null, null,
            URI.create("https://sandbox-api.iyzipay.com"), "a@b.c", "1", "c", "c", "a");

    private final PaymentGatewayConfig config = new PaymentGatewayConfig();

    @Test
    void demoModeUsesTheSimulatedProviderAndProductionNone() {
        assertThat(config.onlinePayments(properties(null, false), NO_KEYS, true).simulated()).isPresent();
        assertThat(config.onlinePayments(properties(null, false), NO_KEYS, false).enabled()).isFalse();
    }

    @Test
    void refusesTheSimulatedProviderInProductionUnlessAllowed() {
        assertThatThrownBy(() -> config.onlinePayments(properties(Provider.SIMULATED, false), NO_KEYS, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIMULATED");
        assertThat(config.onlinePayments(properties(Provider.SIMULATED, true), NO_KEYS, false).enabled()).isTrue();
    }

    @Test
    void iyzicoNeedsItsKeys() {
        assertThatThrownBy(() -> config.onlinePayments(properties(Provider.IYZICO, false), NO_KEYS, true))
                .hasMessageContaining("IYZICO_API_KEY");
    }

    private static PaymentProperties properties(Provider provider, boolean allowSimulated) {
        return new PaymentProperties(provider, allowSimulated, null, Duration.ofHours(2), Duration.ofMinutes(30));
    }
}
