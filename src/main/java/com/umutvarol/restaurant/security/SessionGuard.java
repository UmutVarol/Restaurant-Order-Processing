package com.umutvarol.restaurant.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Ends sessions when an account changes: a token is refused once its account is disabled or gone, or when it carries
 * an older session version than the account (raised when its role changes or its password is reset or changed). Without this a dismissed
 * employee's token would keep working until it expired, hours later.
 * <p>
 * Each account's state is cached for a few seconds and dropped at once when this instance changes it; with
 * several instances, a change made on another one takes effect within {@link #FRESHNESS}.
 */
@Component
class SessionGuard implements OAuth2TokenValidator<Jwt> {

    static final Duration FRESHNESS = Duration.ofSeconds(15);
    private static final OAuth2Error ENDED = new OAuth2Error("invalid_token", "The session has ended", null);

    private record State(boolean enabled, int version) {
    }

    private final AppUserRepository users;
    private final SecurityAuditLog audit;
    private final Cache<String, Optional<State>> states = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(FRESHNESS)
            .build();

    SessionGuard(AppUserRepository users, SecurityAuditLog audit) {
        this.users = users;
        this.audit = audit;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String username = token.getSubject();
        if (username == null) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        Optional<State> state = states.get(username.toLowerCase(java.util.Locale.ROOT), key -> users.findByUsernameIgnoreCase(username)
                .map(user -> new State(user.isEnabled(), user.getSessionVersion())));
        Object claim = token.getClaim(JwtTokenService.SESSION_VERSION_CLAIM);
        // tokens from before versions existed carry none: they belong to version 0
        int version = claim instanceof Number number ? number.intValue() : 0;
        boolean valid = state.isPresent() && state.get().enabled() && state.get().version() == version;
        if (!valid) {
            audit.staleSessionRejected(username);
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** Call after changing an account, so this instance stops accepting its old tokens at once. */
    void forget(String username) {
        states.invalidate(username.toLowerCase(java.util.Locale.ROOT));
    }
}
