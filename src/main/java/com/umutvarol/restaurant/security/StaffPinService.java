package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.crypto.SecretTokens;
import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.security.dto.DeviceRegistration;
import com.umutvarol.restaurant.security.dto.DeviceResponse;
import com.umutvarol.restaurant.security.dto.StaffTile;
import com.umutvarol.restaurant.security.dto.TokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick staff switching on a shared till. A PIN is only accepted from a device a manager registered, is counted
 * against the same lockout as passwords (under its own key, so a PIN attack cannot lock out the password), and is
 * cleared after a few lockouts in a row. A PIN session can never move money out: step-up actions ask for the
 * password ({@link StepUpAuthentication}).
 */
@Service
public class StaffPinService {

    /** PIN lockouts in a row after which the PIN is cleared and must be set again with the password. */
    static final int PIN_LOCKOUT_LIMIT = 3;

    private final AppUserRepository users;
    private final PosDeviceRepository devices;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;
    private final JwtTokenService tokens;
    private final SecurityAuditLog audit;
    private final Clock clock;
    /** Checked against when the user does not exist, so the answer takes as long either way. */
    private final String decoyHash;

    StaffPinService(AppUserRepository users, PosDeviceRepository devices, PasswordEncoder encoder,
                    LoginAttemptService attempts, JwtTokenService tokens, SecurityAuditLog audit, Clock clock) {
        this.users = users;
        this.devices = devices;
        this.encoder = encoder;
        this.attempts = attempts;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
        this.decoyHash = encoder.encode(SecretTokens.newToken());
    }

    // ------------------------------------------------------------------------------------------- PINs

    /** Sets the caller's own PIN. The password is required, so a PIN session cannot change a PIN. */
    @Transactional(noRollbackFor = {AccessRefusedException.class, TooManyRequestsException.class})
    public void setPin(String username, String password, String pin, String client) {
        lockCheck(username);
        AppUser user = users.findByUsernameIgnoreCase(username).orElseThrow(() -> new ResourceNotFoundException("user", username));
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
        requireStrong(pin);
        user.setPinHash(encoder.encode(pin));
        audit.pinSet(user.getUsername());
    }

    /** Demo data only: gives the demo accounts known PINs. */
    @Transactional
    public void demoPins(Map<String, String> pinsByUsername) {
        pinsByUsername.forEach((username, pin) -> users.findByUsernameIgnoreCase(username)
                .ifPresent(user -> user.setPinHash(encoder.encode(pin))));
    }

    /** Signs in with a PIN from a registered device. */
    @Transactional(noRollbackFor = {BadCredentialsException.class, TooManyRequestsException.class})
    public TokenResponse pinLogin(@Nullable String deviceToken, String username, String pin, String client) {
        PosDevice device = device(deviceToken);
        AppUser user = checkPin(username, pin, device.getName(), client);
        audit.pinLogin(user.getUsername(), device.getName(), client);
        return tokens.issue(user, JwtTokenService.PIN);
    }

    /**
     * A manager approving a waiter's void, comp or discount on the spot, by entering their PIN on the till.
     *
     * @return the approving manager's username
     */
    @Transactional(noRollbackFor = {AccessRefusedException.class, TooManyRequestsException.class})
    public String approveAsManager(String manager, String pin, String requestedBy, String client) {
        AppUser user;
        try {
            user = checkPin(manager, pin, "approval for " + requestedBy, client);
        } catch (BadCredentialsException ex) {
            throw new AccessRefusedException("APPROVAL_DENIED", "The manager PIN is not correct.");
        }
        if (user.getRole() != Role.ADMIN) {
            throw new AccessRefusedException("APPROVAL_DENIED", "Only a manager can approve this.");
        }
        return user.getUsername();
    }

    /** The staff who can switch in on this device. */
    @Transactional
    public List<StaffTile> staff(@Nullable String deviceToken) {
        device(deviceToken);
        return users.findByEnabledTrueAndPinHashIsNotNullOrderByDisplayNameAsc().stream()
                .map(user -> new StaffTile(user.getUsername(), user.getDisplayName(), user.getRole()))
                .toList();
    }

    private AppUser checkPin(String username, String pin, String where, String client) {
        String key = "pin:" + username;
        lockCheck(key);
        Optional<AppUser> found = users.findByUsernameIgnoreCase(username).filter(AppUser::isEnabled).filter(AppUser::hasPin);
        boolean matches = encoder.matches(pin, found.map(AppUser::getPinHash).orElse(decoyHash)) && found.isPresent();
        if (!matches) {
            audit.pinFailed(username, where, client);
            Optional<Duration> lockout = attempts.failed(key);
            if (lockout.isPresent()) {
                audit.accountLocked(key, client, lockout.get());
                found.filter(user -> user.pinLockedOut(PIN_LOCKOUT_LIMIT)).ifPresent(user -> audit.pinCleared(user.getUsername()));
                throw locked(lockout.get());
            }
            throw new BadCredentialsException("Invalid username or PIN");
        }
        attempts.succeeded(key);
        AppUser user = found.get();
        user.pinAccepted();
        return user;
    }

    private void lockCheck(String key) {
        attempts.lockedFor(key).ifPresent(remaining -> {
            throw locked(remaining);
        });
    }

    private static TooManyRequestsException locked(Duration retryAfter) {
        return new TooManyRequestsException("LOGIN_LOCKED", "Too many failed attempts. Try again later.", retryAfter);
    }

    /** No repeated digits (1111), no runs (1234, 9876) and no repeated pairs (1212): the first PINs an attacker tries. */
    static void requireStrong(String pin) {
        boolean repeated = pin.chars().distinct().count() == 1;
        boolean run = true;
        boolean reverseRun = true;
        for (int i = 1; i < pin.length(); i++) {
            run &= pin.charAt(i) == pin.charAt(i - 1) + 1;
            reverseRun &= pin.charAt(i) == pin.charAt(i - 1) - 1;
        }
        boolean pairs = pin.length() % 2 == 0 && pin.equals(pin.substring(0, 2).repeat(pin.length() / 2));
        if (repeated || run || reverseRun || pairs) {
            throw new BusinessRuleException("PIN_TOO_SIMPLE", "Choose a PIN that is not a run or a repeat.");
        }
    }

    // ------------------------------------------------------------------------------------------- devices

    @Transactional
    public DeviceRegistration register(String name, String registeredBy) {
        String token = SecretTokens.newToken();
        PosDevice device = devices.save(new PosDevice(name.strip(), SecretTokens.hash(token), registeredBy, clock.instant()));
        audit.deviceRegistered(device.getId(), device.getName(), registeredBy);
        return new DeviceRegistration(DeviceResponse.from(device), token);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> devices() {
        return devices.findAllByOrderByCreatedAtDesc().stream().map(DeviceResponse::from).toList();
    }

    @Transactional
    public DeviceResponse revoke(long id, String revokedBy) {
        PosDevice device = devices.findById(id).orElseThrow(() -> new ResourceNotFoundException("device", id));
        device.revoke(clock.instant());
        audit.deviceRevoked(device.getId(), device.getName(), revokedBy);
        return DeviceResponse.from(device);
    }

    private PosDevice device(@Nullable String token) {
        PosDevice device = token == null || token.isBlank() || token.length() > 100 ? null
                : devices.findByTokenHash(SecretTokens.hash(token)).filter(PosDevice::isActive).orElse(null);
        if (device == null) {
            throw new AccessRefusedException("DEVICE_NOT_REGISTERED", "This device is not registered for PIN sign-in.");
        }
        device.seen(clock.instant());
        return device;
    }
}
