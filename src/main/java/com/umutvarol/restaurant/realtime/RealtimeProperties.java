package com.umutvarol.restaurant.realtime;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Caps on open live-update streams. Every stream holds a connection open for up to half an hour, so without a
 * cap a single client could open thousands and starve everyone else (OWASP Denial of Service Cheat Sheet,
 * "define a load limit").
 *
 * @param maxSubscribers        streams across all users
 * @param maxSubscribersPerUser streams one signed-in user may hold, e.g. a few screens and browser tabs
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.realtime")
public record RealtimeProperties(
        @Min(1) @DefaultValue("500") int maxSubscribers,
        @Min(1) @DefaultValue("10") int maxSubscribersPerUser) {
}
