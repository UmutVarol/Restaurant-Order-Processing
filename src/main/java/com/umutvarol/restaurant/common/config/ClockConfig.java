package com.umutvarol.restaurant.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a {@link Clock} bean so that time-dependent logic (order timestamps, reports, coupon expiry)
 * can be tested deterministically with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock(RestaurantProperties properties) {
        return Clock.system(properties.timeZone());
    }
}
