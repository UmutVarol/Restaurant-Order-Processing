package com.umutvarol.restaurant.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the HMAC-signed access tokens issued at sign-in.
 *
 * @param secret     shared HS256 key, at least 32 characters. <b>Override in production</b> via {@code JWT_SECRET}.
 * @param issuer     value of the {@code iss} claim, verified on every request
 * @param timeToLive how long a token stays valid — one restaurant shift by default
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters for HS256") String secret,
        @DefaultValue("sofra") @NotBlank String issuer,
        @DefaultValue("8h") @NotNull Duration timeToLive) {
}
