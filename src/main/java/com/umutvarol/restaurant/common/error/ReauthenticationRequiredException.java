package com.umutvarol.restaurant.common.error;

import java.util.Map;

/**
 * The action needs a recent sign-in (OWASP Transaction Authorization: re-authenticate before sensitive
 * operations). Mapped to {@code 403} with code {@code REAUTH_REQUIRED}, so the client asks for the password again
 * rather than treating the session as expired.
 */
public class ReauthenticationRequiredException extends DomainException {

    public ReauthenticationRequiredException(long windowMinutes) {
        super("REAUTH_REQUIRED", "Confirm your password to continue.", Map.of("windowMinutes", windowMinutes));
    }
}
