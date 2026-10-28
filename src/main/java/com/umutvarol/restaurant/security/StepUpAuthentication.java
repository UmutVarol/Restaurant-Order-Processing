package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.ReauthenticationRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Step-up authentication for actions that move money out (refunds) or cannot be undone (closing the day): the
 * caller must have signed in within the last few minutes. A token left signed in on an unattended till, or
 * stolen from it, is then not enough on its own.
 */
@Component
public class StepUpAuthentication {

    private final Duration window;
    private final Clock clock;

    public StepUpAuthentication(AbuseProtectionProperties properties, Clock clock) {
        this.window = properties.stepUpWindow();
        this.clock = clock;
    }

    /**
     * A PIN sign-in never counts: four digits watched over a shoulder must not be enough to move money.
     *
     * @throws ReauthenticationRequiredException if the caller did not sign in with a password within the window
     */
    public void requireRecentSignIn(Authentication authentication) {
        Jwt token = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken() : null;
        Instant signedIn = token == null ? null : token.getIssuedAt();
        List<String> methods = token == null ? null : token.getClaimAsStringList(JwtTokenService.AMR_CLAIM);
        if (signedIn == null || signedIn.isBefore(clock.instant().minus(window))
                || methods == null || !methods.contains(JwtTokenService.PASSWORD)) {
            throw new ReauthenticationRequiredException(Math.max(1, window.toMinutes()));
        }
    }
}
