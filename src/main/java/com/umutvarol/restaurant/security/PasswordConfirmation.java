package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import java.time.Duration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Asks a signed-in user for their password again before a change to how they sign in (a PIN, two-step sign-in).
 * Wrong answers count against the same lockout as the sign-in screen, so this is no way around it.
 */
@Component
class PasswordConfirmation {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;
    private final SecurityAuditLog audit;

    PasswordConfirmation(AppUserRepository users, PasswordEncoder encoder, LoginAttemptService attempts,
                         SecurityAuditLog audit) {
        this.users = users;
        this.encoder = encoder;
        this.attempts = attempts;
        this.audit = audit;
    }

    /** @return the user, once the password matched */
    AppUser confirm(String username, String password, String client) {
        attempts.lockedFor(username).ifPresent(remaining -> {
            throw locked(remaining);
        });
        AppUser user = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("user", username));
        if (!encoder.matches(password, user.getPasswordHash())) {
            audit.loginFailed(username, client);
            attempts.failed(username).ifPresent(lockout -> {
                audit.accountLocked(username, client, lockout);
                throw locked(lockout);
            });
            // 403, not 401: a wrong password here must not end the session the request was made with.
            throw new AccessRefusedException("PASSWORD_INCORRECT", "The password is not correct.");
        }
        attempts.succeeded(username);
        return user;
    }

    static TooManyRequestsException locked(Duration retryAfter) {
        return new TooManyRequestsException("LOGIN_LOCKED", "Too many failed attempts. Try again later.", retryAfter);
    }
}
