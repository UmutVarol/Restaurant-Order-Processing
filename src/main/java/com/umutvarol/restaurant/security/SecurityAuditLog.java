package com.umutvarol.restaurant.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes security events to a dedicated {@code security.audit} logger and counts them in Micrometer, so failed
 * sign-ins, lockouts and revoked tokens can be reviewed and alerted on (OWASP Logging and Authentication cheat
 * sheets). User-supplied values are stripped of control characters first, so they cannot forge log lines.
 */
@Component
class SecurityAuditLog {

    private static final Logger audit = LoggerFactory.getLogger("security.audit");

    private final MeterRegistry meters;

    SecurityAuditLog(MeterRegistry meters) {
        this.meters = meters;
    }

    void loginSucceeded(String username, String client) {
        count("success");
        audit.info("login.success user={} client={}", clean(username), clean(client));
    }

    void loginFailed(String username, String client) {
        count("failure");
        audit.warn("login.failure user={} client={}", clean(username), clean(client));
    }

    void accountLocked(String username, String client, Duration lockout) {
        count("locked");
        audit.warn("login.locked user={} client={} lockoutSeconds={}", clean(username), clean(client), lockout.toSeconds());
    }

    void loginRejectedWhileLocked(String username, String client) {
        count("rejected_locked");
        audit.warn("login.rejected_locked user={} client={}", clean(username), clean(client));
    }

    void loggedOut(String username, String tokenId) {
        meters.counter("sofra.security.logout").increment();
        audit.info("logout user={} jti={}", clean(username), clean(tokenId));
    }

    void pinLogin(String username, String device, String client) {
        count("pin_success");
        audit.info("login.pin user={} device={} client={}", clean(username), clean(device), clean(client));
    }

    void pinFailed(String username, String where, String client) {
        count("pin_failure");
        audit.warn("login.pin_failure user={} at={} client={}", clean(username), clean(where), clean(client));
    }

    void pinSet(String username) {
        audit.info("pin.set user={}", clean(username));
    }

    void pinCleared(String username) {
        meters.counter("sofra.security.pin_cleared").increment();
        audit.warn("pin.cleared user={} reason=repeated_lockouts", clean(username));
    }

    void totpEnabled(String username) {
        audit.info("totp.enabled user={}", clean(username));
    }

    void totpDisabled(String username) {
        audit.warn("totp.disabled user={}", clean(username));
    }

    void recoveryCodeUsed(String username) {
        meters.counter("sofra.security.recovery_code_used").increment();
        audit.warn("totp.recovery_code_used user={}", clean(username));
    }

    void totpFailed(String username, String client) {
        count("totp_failure");
        audit.warn("login.totp_failure user={} client={}", clean(username), clean(client));
    }

    void deviceRegistered(Long id, String name, String by) {
        audit.info("device.registered id={} name={} by={}", id, clean(name), clean(by));
    }

    void deviceRevoked(Long id, String name, String by) {
        audit.warn("device.revoked id={} name={} by={}", id, clean(name), clean(by));
    }

    private void count(String outcome) {
        meters.counter("sofra.security.login", "outcome", outcome).increment();
    }

    /** Neutralises CR, LF and other control characters (log injection) and caps the length. */
    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "_");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) + "…" : cleaned;
    }
}
