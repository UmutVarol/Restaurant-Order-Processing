package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.security.dto.TotpSetupResponse;
import com.umutvarol.restaurant.security.dto.TwoFactorStatus;
import com.umutvarol.restaurant.security.totp.Base32;
import com.umutvarol.restaurant.security.totp.SecretBox;
import com.umutvarol.restaurant.security.totp.Totp;
import com.umutvarol.restaurant.security.totp.TotpProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-step sign-in for managers with an authenticator app (TOTP, RFC 6238). The shared secret is encrypted at rest;
 * enrolment is only complete once a first code proves the app has it; ten one-time recovery codes, stored as
 * hashes, stand in for a lost phone. Turning it on or off needs the password and a code.
 */
@Service
@Transactional
public class TwoFactorService {

    static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder encoder;
    private final PasswordConfirmation passwords;
    private final SecretBox box;
    private final TotpProperties properties;
    private final SecurityAuditLog audit;
    private final Clock clock;

    TwoFactorService(AppUserRepository users, RecoveryCodeRepository recoveryCodes, PasswordEncoder encoder,
                     PasswordConfirmation passwords, SecretBox box, TotpProperties properties, SecurityAuditLog audit,
                     Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.encoder = encoder;
        this.passwords = passwords;
        this.box = box;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TwoFactorStatus status(String username) {
        AppUser user = find(username);
        return new TwoFactorStatus(user.isTotpEnabled(), box.available(), setupRequired(user),
                user.isTotpEnabled() ? recoveryCodes.findByUserIdAndUsedAtIsNull(user.getId()).size() : 0);
    }

    /** Starts enrolment after the password: a new secret, returned once for the QR code. */
    public TotpSetupResponse setup(String username, String password, String client) {
        requireAvailable();
        AppUser user = passwords.confirm(username, password, client);
        if (user.isTotpEnabled()) {
            throw new BusinessRuleException("TOTP_ALREADY_ENABLED", "Two-step sign-in is already on; turn it off first");
        }
        byte[] secret = Totp.newSecret();
        user.startTotp(box.seal(secret));
        return new TotpSetupResponse(Base32.encode(secret), Totp.uri(properties.issuer(), user.getUsername(), secret));
    }

    /** Finishes enrolment with a first code from the app. @return the recovery codes, shown once */
    public List<String> enable(String username, String code) {
        requireAvailable();
        AppUser user = find(username);
        if (user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new BusinessRuleException("TOTP_NOT_PENDING", "Start the setup first");
        }
        OptionalLong step = Totp.verify(box.open(user.getTotpSecret()), code, clock.instant(), 0);
        if (step.isEmpty()) {
            throw new AccessRefusedException("TOTP_INVALID", "That code is not right.");
        }
        user.enableTotp(step.getAsLong());
        audit.totpEnabled(user.getUsername());
        return newRecoveryCodes(user);
    }

    public void disable(String username, String password, String code, String client) {
        AppUser user = passwords.confirm(username, password, client);
        requireCode(user, code);
        user.disableTotp();
        recoveryCodes.deleteByUserId(user.getId());
        audit.totpDisabled(user.getUsername());
    }

    public List<String> regenerateRecoveryCodes(String username, String code) {
        AppUser user = find(username);
        requireCode(user, code);
        return newRecoveryCodes(user);
    }

    /** A code at sign-in: from the authenticator app, or one of the recovery codes (then used up). */
    public boolean verify(AppUser user, String code) {
        if (!user.isTotpEnabled() || code == null) {
            return false;
        }
        String trimmed = code.strip();
        if (trimmed.matches("\\d{" + Totp.DIGITS + "}")) {
            OptionalLong step = Totp.verify(box.open(user.getTotpSecret()), trimmed, clock.instant(), user.getTotpLastStep());
            step.ifPresent(user::totpUsed);
            users.save(user);
            return step.isPresent();
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.length() != 10) {
            return false;
        }
        for (RecoveryCode recovery : recoveryCodes.findByUserIdAndUsedAtIsNull(user.getId())) {
            if (encoder.matches(normalized, recovery.getCodeHash())) {
                recovery.use(clock.instant());
                audit.recoveryCodeUsed(user.getUsername());
                return true;
            }
        }
        return false;
    }

    /** Whether this manager must set up two-step sign-in before money-sensitive actions. */
    public boolean setupRequired(AppUser user) {
        return setupRequired(properties, user);
    }

    static boolean setupRequired(TotpProperties properties, AppUser user) {
        return properties.requiredForManagers() && user.getRole() == Role.ADMIN && !user.isTotpEnabled();
    }

    private void requireCode(AppUser user, String code) {
        if (!user.isTotpEnabled()) {
            throw new BusinessRuleException("TOTP_NOT_ENABLED", "Two-step sign-in is not on");
        }
        if (!verify(user, code)) {
            throw new AccessRefusedException("TOTP_INVALID", "That code is not right.");
        }
    }

    private List<String> newRecoveryCodes(AppUser user) {
        recoveryCodes.deleteByUserId(user.getId());
        recoveryCodes.flush();
        Instant now = clock.instant();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder raw = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                raw.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            recoveryCodes.save(new RecoveryCode(user.getId(), encoder.encode(raw.toString()), now));
            codes.add(raw.substring(0, 5) + "-" + raw.substring(5));
        }
        return codes;
    }

    private void requireAvailable() {
        if (!box.available()) {
            throw new BusinessRuleException("TOTP_NOT_CONFIGURED", "Two-step sign-in needs TOTP_ENCRYPTION_KEY on the server");
        }
    }

    private AppUser find(String username) {
        return users.findByUsernameIgnoreCase(username).orElseThrow(() -> new ResourceNotFoundException("user", username));
    }
}
