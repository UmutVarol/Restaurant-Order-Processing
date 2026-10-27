package com.umutvarol.restaurant.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Limits that keep the API usable under abuse, following the OWASP Authentication, REST Security and Denial of
 * Service cheat sheets.
 *
 * @param login          per-account lockout after repeated failed sign-ins
 * @param rateLimit      per-client request budgets
 * @param maxRequestBody largest request body the API accepts; bigger ones get 413
 * @param stepUpWindow   how recent a sign-in must be for money-sensitive actions (refunds, closing the day)
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.security")
public record AbuseProtectionProperties(
        @Valid @NotNull @DefaultValue Login login,
        @Valid @NotNull @DefaultValue RateLimit rateLimit,
        @NotNull @DefaultValue("64KB") DataSize maxRequestBody,
        @NotNull @DefaultValue("5m") Duration stepUpWindow) {

    /**
     * Failed sign-ins are counted per account, not per IP address, so spreading a guessing attack over many
     * addresses does not help. Lockouts are short and grow exponentially, so the lockout itself cannot be
     * used to keep a real user out for long.
     *
     * @param maxFailures       failures within the window that trigger a lockout
     * @param observationWindow how long failures are remembered
     * @param baseLockout       first lockout; each further lockout doubles it
     * @param maxLockout        upper bound for a lockout
     */
    public record Login(
            @Min(1) @DefaultValue("5") int maxFailures,
            @NotNull @DefaultValue("15m") Duration observationWindow,
            @NotNull @DefaultValue("30s") Duration baseLockout,
            @NotNull @DefaultValue("15m") Duration maxLockout) {
    }

    /**
     * Token buckets per client address. The defaults leave plenty of room for a busy restaurant whose tablets
     * share one public address, while stopping floods and scripted password guessing.
     *
     * @param enabled                 switch for local load testing
     * @param apiRequestsPerMinute    budget for every {@code /api/**} call
     * @param loginRequestsPerMinute  separate, tighter budget for the sign-in endpoint
     * @param publicRequestsPerMinute separate budget for what needs no sign-in: the guest payment page, provider
     *                                callbacks and notifications, and browser security reports
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("600") int apiRequestsPerMinute,
            @Min(1) @DefaultValue("60") int loginRequestsPerMinute,
            @Min(1) @DefaultValue("120") int publicRequestsPerMinute) {
    }
}
