package com.umutvarol.restaurant.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * General restaurant settings.
 *
 * @param name     display name of the restaurant, printed on receipts
 * @param timeZone the time zone the restaurant operates in; defines "today" for reports
 */
@Validated
@ConfigurationProperties(prefix = "restaurant")
public record RestaurantProperties(
        @DefaultValue("Sofra Bistro") @NotBlank String name,
        @DefaultValue("Europe/Istanbul") @NotNull ZoneId timeZone) {
}
