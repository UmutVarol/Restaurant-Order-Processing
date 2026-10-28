package com.umutvarol.restaurant.printing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Network printers. The server opens connections to addresses staff type in, so only private-network addresses
 * and a narrow port range are allowed: the printer setting can never be used to reach the internet or a cloud
 * metadata service (SSRF).
 *
 * @param enabled        switch off to keep tickets queued (e.g. in a demo with no printers)
 * @param minPort        lowest allowed port; raw printing (JetDirect) uses 9100
 * @param maxPort        highest allowed port
 * @param connectTimeout how long to wait for a printer to answer
 * @param writeTimeout   how long a ticket may take to send
 * @param maxAttempts    tries before a ticket is marked failed
 * @param language       ticket language, {@code tr} or {@code en}
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.printing")
public record PrintingProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @Max(65535) @DefaultValue("9100") int minPort,
        @Min(1) @Max(65535) @DefaultValue("9109") int maxPort,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("5s") Duration writeTimeout,
        @Min(1) @Max(20) @DefaultValue("5") int maxAttempts,
        @NotNull @DefaultValue("tr") String language) {
}
