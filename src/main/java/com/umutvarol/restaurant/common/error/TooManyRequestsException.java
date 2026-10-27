package com.umutvarol.restaurant.common.error;

import java.time.Duration;
import java.util.Map;

/**
 * The caller has to slow down: a rate limit was hit or the account is temporarily locked. Mapped to
 * {@code 429 Too Many Requests} with a {@code Retry-After} header.
 */
public class TooManyRequestsException extends DomainException {

    private final Duration retryAfter;

    public TooManyRequestsException(String code, String message, Duration retryAfter) {
        super(code, message, Map.of("retryAfterSeconds", retryAfterSeconds(retryAfter)));
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Whole seconds, rounded up, and never zero: "retry after 0 seconds" would invite an immediate retry. */
    public static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }
}
