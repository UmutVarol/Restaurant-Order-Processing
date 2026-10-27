package com.umutvarol.restaurant.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Token IDs ({@code jti}) of access tokens that were signed out before they expired (OWASP JWT Cheat Sheet,
 * "JWT denylist"). Each entry lives only until its token would have expired anyway, so the list stays small.
 * <p>
 * The list is kept in memory: with several application instances, back it with a shared store such as Redis.
 */
@Component
class TokenDenylist {

    private final Clock clock;
    private final Cache<String, Instant> revoked;

    TokenDenylist(Clock clock) {
        this.clock = clock;
        this.revoked = Caffeine.newBuilder()
                .maximumSize(1_000_000)
                .expireAfter(Expiry.creating((String jti, Instant expiresAt) -> {
                    Duration left = Duration.between(clock.instant(), expiresAt);
                    return left.isNegative() ? Duration.ZERO : left;
                }))
                .build();
    }

    void revoke(String tokenId, Instant expiresAt) {
        if (tokenId != null && expiresAt != null && expiresAt.isAfter(clock.instant())) {
            revoked.put(tokenId, expiresAt);
        }
    }

    boolean isRevoked(String tokenId) {
        return tokenId != null && revoked.getIfPresent(tokenId) != null;
    }
}
