package com.umutvarol.restaurant.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Counts failed sign-ins per account and locks the account for a short, exponentially growing period once
 * too many fail within the observation window (OWASP Authentication Cheat Sheet, "Account Lockout").
 * <p>
 * The key is the submitted username, whether or not such an account exists, so a lockout never reveals which
 * usernames are real. State lives in a bounded cache, so a flood of random usernames cannot exhaust memory.
 */
@Service
public class LoginAttemptService {

    private final AbuseProtectionProperties.Login policy;
    private final Clock clock;
    private final Cache<String, Attempts> attempts;

    public LoginAttemptService(AbuseProtectionProperties properties, Clock clock) {
        this.policy = properties.login();
        this.clock = clock;
        this.attempts = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(policy.observationWindow().plus(policy.maxLockout()))
                .build();
    }

    /** How long the account must still wait before it may try again, if it is locked out. */
    public Optional<Duration> lockedFor(String username) {
        Attempts state = attempts.getIfPresent(key(username));
        if (state == null || state.lockedUntil == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(clock.instant(), state.lockedUntil);
        return remaining.isPositive() ? Optional.of(remaining) : Optional.empty();
    }

    /**
     * Records a failed sign-in.
     *
     * @return the lockout that this failure started, if any
     */
    public Optional<Duration> failed(String username) {
        Instant now = clock.instant();
        Attempts state = attempts.asMap().compute(key(username), (key, previous) -> {
            Attempts next = previous == null ? new Attempts() : previous;
            if (next.windowStart == null || now.isAfter(next.windowStart.plus(policy.observationWindow()))) {
                next.windowStart = now;
                next.failures = 0;
            }
            next.failures++;
            next.justLocked = null;
            if (next.failures >= policy.maxFailures()) {
                Duration lockout = lockoutFor(next.lockouts);
                next.lockouts++;
                next.lockedUntil = now.plus(lockout);
                next.failures = 0;
                next.windowStart = null;
                next.justLocked = lockout;
            }
            return next;
        });
        return Optional.ofNullable(state.justLocked);
    }

    /** A successful sign-in clears the account's history. */
    public void succeeded(String username) {
        attempts.invalidate(key(username));
    }

    private Duration lockoutFor(int previousLockouts) {
        Duration lockout = policy.baseLockout().multipliedBy(1L << Math.min(previousLockouts, 20));
        return lockout.compareTo(policy.maxLockout()) > 0 ? policy.maxLockout() : lockout;
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Attempts {
        private Instant windowStart;
        private int failures;
        private int lockouts;
        private Instant lockedUntil;
        private Duration justLocked;
    }
}
