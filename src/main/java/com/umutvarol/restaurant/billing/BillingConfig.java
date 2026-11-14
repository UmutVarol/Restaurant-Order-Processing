package com.umutvarol.restaurant.billing;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The billing provider chosen by {@code restaurant.billing.provider}; none on a restaurant's own server. */
@Configuration
class BillingConfig {

    @Bean
    @ConditionalOnExpression("'${restaurant.billing.provider:NONE}' != 'NONE'")
    BillingProvider billingProvider(BillingProperties properties, Clock clock,
                                    @Value("${restaurant.cloud.enabled:false}") boolean cloud,
                                    @Value("${restaurant.billing.allow-simulated:false}") boolean allowSimulated) {
        return switch (properties.provider()) {
            case PADDLE -> new PaddleBillingProvider(properties, RestClient.builder(), clock);
            case SIMULATED -> {
                // on a server that serves real businesses, a simulation would hand out plans for free
                if (cloud && !allowSimulated) {
                    throw new IllegalStateException("The simulated billing provider is refused with Sofra Cloud on; "
                            + "set restaurant.billing.allow-simulated=true only for a test server.");
                }
                yield new SimulatedBillingProvider();
            }
            case NONE -> throw new IllegalStateException("unreachable");
        };
    }
}
