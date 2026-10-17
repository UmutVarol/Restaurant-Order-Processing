package com.umutvarol.restaurant.bootstrap;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Demo mode: creates the demo staff accounts and fills an empty database with realistic order history so the
 * dashboard, kitchen display and floor plan have something to show. Disable in production.
 *
 * @param enabled     whether demo accounts and orders are created on start-up
 * @param historyDays how many days of past orders to generate
 * @param seed        random seed, so every start produces the same shape of data
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.demo")
public record DemoProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("14") @Min(0) @Max(90) int historyDays,
        @DefaultValue("20260101") long seed) {
}
