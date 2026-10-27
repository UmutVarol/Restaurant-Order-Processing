package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.payment.PaymentProperties.Provider;
import com.umutvarol.restaurant.payment.gateway.IyzicoGateway;
import com.umutvarol.restaurant.payment.gateway.IyzicoProperties;
import com.umutvarol.restaurant.payment.gateway.SimulatedGateway;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Picks the online payment provider. The simulated provider lets anyone mark a payment as paid, so with demo mode
 * off the app refuses to start with it unless {@code restaurant.payments.allow-simulated} says otherwise.
 */
@Configuration(proxyBeanMethods = false)
class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    OnlinePayments onlinePayments(PaymentProperties properties, IyzicoProperties iyzico,
                                  // Read as a plain property: nothing may depend on the bootstrap package.
                                  @Value("${restaurant.demo.enabled:true}") boolean demoMode) {
        Provider provider = properties.provider() != null ? properties.provider()
                : demoMode ? Provider.SIMULATED : Provider.NONE;
        return switch (provider) {
            case NONE -> {
                log.info("Online payments are off. Set PAYMENT_PROVIDER=IYZICO to take QR payments.");
                yield new OnlinePayments(null);
            }
            case SIMULATED -> {
                if (!demoMode && !properties.allowSimulated()) {
                    throw new IllegalStateException("PAYMENT_PROVIDER=SIMULATED lets anyone mark bills as paid and "
                            + "is refused with demo mode off. Use IYZICO or NONE, or set "
                            + "restaurant.payments.allow-simulated=true on a test server.");
                }
                log.warn("Online payments use the simulated provider: nothing is charged.");
                yield new OnlinePayments(new SimulatedGateway());
            }
            case IYZICO -> new OnlinePayments(new IyzicoGateway(iyzico, RestClient.builder()
                    .requestFactory(requestFactory())));
        };
    }

    /** A provider that hangs must not hold up the till: short connect and read timeouts. */
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }
}
