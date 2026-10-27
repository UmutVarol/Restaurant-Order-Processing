package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.ReauthenticationRequiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
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

    /** @throws ReauthenticationRequiredException if the caller's sign-in is older than the window */
    public void requireRecentSignIn(Authentication authentication) {
        Instant signedIn = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getIssuedAt() : null;
        if (signedIn == null || signedIn.isBefore(clock.instant().minus(window))) {
            throw new ReauthenticationRequiredException(Math.max(1, window.toMinutes()));
        }
    }
}
