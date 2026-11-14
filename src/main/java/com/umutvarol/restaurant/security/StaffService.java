package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.security.dto.StaffCredentials;
import com.umutvarol.restaurant.security.dto.StaffRequest;
import com.umutvarol.restaurant.security.dto.StaffResponse;
import com.umutvarol.restaurant.security.dto.StaffUpdateRequest;
import com.umutvarol.restaurant.security.dto.TokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Managers add, change and switch off staff accounts. New accounts and password resets get a temporary password,
 * shown once, which the employee replaces at the first sign-in. Anything that changes what an account may do ends
 * its sessions at once. A manager cannot lock themselves out, and the restaurant always keeps one active manager.
 * Granting or touching manager accounts and resetting passwords need a recent password sign-in (step-up).
 */
@Service
@Transactional
public class StaffService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SessionGuard sessions;
    private final StepUpAuthentication stepUp;
    private final PasswordConfirmation confirmation;
    private final JwtTokenService tokens;
    private final SecurityAuditLog audit;
    private final Clock clock;

    StaffService(AppUserRepository users, PasswordEncoder encoder, SessionGuard sessions, StepUpAuthentication stepUp,
                 PasswordConfirmation confirmation, JwtTokenService tokens, SecurityAuditLog audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
        this.stepUp = stepUp;
        this.confirmation = confirmation;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StaffResponse> list() {
        return users.findAllByOrderByEnabledDescDisplayNameAsc().stream().map(StaffService::toResponse).toList();
    }

    public StaffCredentials create(StaffRequest request, Authentication actor) {
        if (request.role() == Role.ADMIN) {
            stepUp.requireRecentSignIn(actor);
        }
        if (users.findByUsernameIgnoreCase(request.username()).isPresent()) {
            throw new InvalidStateException("USERNAME_TAKEN", "That username is already in use",
                    Map.of("username", request.username()));
        }
        String temporary = PasswordPolicy.temporary();
        AppUser user = users.save(AppUser.withTemporaryPassword(request.username(), encoder.encode(temporary),
                request.displayName().strip(), request.role(), clock.instant()));
        if (Boolean.FALSE.equals(request.enabled())) {
            user.update(user.getDisplayName(), user.getRole(), false, clock.instant());
        }
        audit.staffChanged("created", user.getUsername(), "role=" + user.getRole(), actor.getName());
        return new StaffCredentials(toResponse(user), temporary);
    }

    public StaffResponse update(Long id, StaffUpdateRequest request, Authentication actor) {
        AppUser user = find(id);
        boolean self = user.getUsername().equalsIgnoreCase(actor.getName());
        if (self && (request.role() != user.getRole() || !request.enabled())) {
            throw new BusinessRuleException("STAFF_SELF_LOCKOUT",
                    "You cannot change your own role or switch off your own account");
        }
        if (user.getRole() == Role.ADMIN || request.role() == Role.ADMIN) {
            if (!self) {
                stepUp.requireRecentSignIn(actor);
            }
            boolean losesManager = user.getRole() == Role.ADMIN && user.isEnabled()
                    && (request.role() != Role.ADMIN || !request.enabled());
            if (losesManager && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
                throw new BusinessRuleException("LAST_MANAGER", "The restaurant needs at least one active manager");
            }
        }
        boolean access = user.update(request.displayName().strip(), request.role(), request.enabled(), clock.instant());
        if (access) {
            sessions.forget(user.getUsername());
            audit.staffChanged("access_changed", user.getUsername(),
                    "role=" + user.getRole() + " enabled=" + user.isEnabled(), actor.getName());
        }
        return toResponse(user);
    }

    /** A new temporary password; every session of the account ends. */
    public StaffCredentials resetPassword(Long id, Authentication actor) {
        stepUp.requireRecentSignIn(actor);
        AppUser user = find(id);
        if (user.getUsername().equalsIgnoreCase(actor.getName())) {
            throw new BusinessRuleException("STAFF_RESET_SELF", "Change your own password in Settings instead");
        }
        String temporary = PasswordPolicy.temporary();
        user.resetPassword(encoder.encode(temporary), clock.instant());
        sessions.forget(user.getUsername());
        audit.staffChanged("password_reset", user.getUsername(), "", actor.getName());
        return new StaffCredentials(toResponse(user), temporary);
    }

    /** For a lost or shared PIN: they set a new one in Settings. */
    public StaffResponse clearPin(Long id, Authentication actor) {
        AppUser user = find(id);
        user.clearPin();
        audit.staffChanged("pin_cleared", user.getUsername(), "", actor.getName());
        return toResponse(user);
    }

    /** The owner chooses a new password; their other sessions end and this one gets a fresh token. */
    public TokenResponse changeOwnPassword(String username, String current, String next, String client) {
        AppUser user = confirmation.confirm(username, current, client);
        PasswordPolicy.check(user.getUsername(), next);
        if (encoder.matches(next, user.getPasswordHash())) {
            throw new BusinessRuleException("PASSWORD_UNCHANGED", "Choose a password different from the current one");
        }
        Instant now = clock.instant();
        user.changePassword(encoder.encode(next), now);
        sessions.forget(user.getUsername());
        audit.passwordChanged(user.getUsername(), client);
        return tokens.issue(user);
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("staff", id));
    }

    static StaffResponse toResponse(AppUser user) {
        return new StaffResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.isEnabled(), user.hasPin(), user.isTotpEnabled(), user.isMustChangePassword(), user.getCreatedAt());
    }
}
