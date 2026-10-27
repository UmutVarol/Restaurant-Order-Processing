package com.umutvarol.restaurant.security;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a production deployment (demo mode off) with the development signing key that ships in
 * {@code application.yml}: anyone who has read the source could forge tokens with it. Demo mode only warns.
 */
@Component
class JwtSecretGuard implements InitializingBean {

    static final String DEVELOPMENT_SECRET_PREFIX = "dev-only-secret";
    private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);

    private final JwtProperties jwt;
    private final boolean demoMode;

    // Read as a plain property: the demo settings belong to the bootstrap package, which nothing may depend on.
    JwtSecretGuard(JwtProperties jwt, @Value("${restaurant.demo.enabled:true}") boolean demoMode) {
        this.jwt = jwt;
        this.demoMode = demoMode;
    }

    @Override
    public void afterPropertiesSet() {
        boolean developmentKey = jwt.secret().startsWith(DEVELOPMENT_SECRET_PREFIX);
        if (developmentKey && !demoMode) {
            throw new IllegalStateException("JWT_SECRET is not set: refusing to sign tokens with the public "
                    + "development key. Generate one with `openssl rand -base64 48` and pass it as JWT_SECRET.");
        }
        if (developmentKey) {
            log.warn("Signing tokens with the development JWT key. Set JWT_SECRET before exposing this instance.");
        }
        if (jwt.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits) for HS256.");
        }
    }
}
