package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.security.dto.LoginRequest;
import com.umutvarol.restaurant.security.dto.PinLoginRequest;
import com.umutvarol.restaurant.security.dto.SetPinRequest;
import com.umutvarol.restaurant.security.dto.StaffTile;
import com.umutvarol.restaurant.security.dto.TokenResponse;
import com.umutvarol.restaurant.security.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Sign in and obtain a bearer token")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String DEVICE_HEADER = "X-Device-Token";

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;
    private final JwtTokenService tokens;
    private final LoginAttemptService attempts;
    private final TokenDenylist denylist;
    private final SecurityAuditLog audit;
    private final StaffPinService pins;

    AuthController(AuthenticationManager authenticationManager, AppUserRepository users, JwtTokenService tokens,
                   LoginAttemptService attempts, TokenDenylist denylist, SecurityAuditLog audit, StaffPinService pins) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
        this.attempts = attempts;
        this.denylist = denylist;
        this.audit = audit;
        this.pins = pins;
    }

    @Operation(summary = "Sign in", description = """
            Exchanges username and password for a JWT access token. After repeated failures the account is
            locked for a short, growing period and the endpoint answers 429 with Retry-After.""")
    @SecurityRequirements
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String client = http.getRemoteAddr();
        Optional<Duration> locked = attempts.lockedFor(request.username());
        if (locked.isPresent()) {
            audit.loginRejectedWhileLocked(request.username(), client);
            throw tooManyAttempts(locked.get());
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            audit.loginFailed(request.username(), client);
            Optional<Duration> lockout = attempts.failed(request.username());
            if (lockout.isPresent()) {
                audit.accountLocked(request.username(), client, lockout.get());
                throw tooManyAttempts(lockout.get());
            }
            // Disabled, locked or unknown accounts get exactly the same answer as a wrong password, so the
            // response never tells an attacker which usernames exist (OWASP Authentication Cheat Sheet).
            throw new BadCredentialsException("Invalid username or password", ex);
        }
        attempts.succeeded(request.username());
        audit.loginSucceeded(authentication.getName(), client);
        return tokens.issue(findUser(authentication.getName()));
    }

    @Operation(summary = "Sign in with a PIN", description = """
            Quick staff switching on a till a manager registered (`X-Device-Token`). A PIN session works for
            everyday service but never for refunds or closing the day, which ask for the password.""")
    @SecurityRequirements
    @PostMapping("/pin-login")
    TokenResponse pinLogin(@RequestHeader(name = DEVICE_HEADER, required = false) String device,
                           @Valid @RequestBody PinLoginRequest request, HttpServletRequest http) {
        return pins.pinLogin(device, request.username(), request.pin(), http.getRemoteAddr());
    }

    @Operation(summary = "Staff who can switch in on this till")
    @SecurityRequirements
    @GetMapping("/pin-staff")
    List<StaffTile> pinStaff(@RequestHeader(name = DEVICE_HEADER, required = false) String device) {
        return pins.staff(device);
    }

    @Operation(summary = "Set my PIN", description = "Needs the password, so a PIN session cannot change a PIN.")
    @PutMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPin(Authentication authentication, @Valid @RequestBody SetPinRequest request, HttpServletRequest http) {
        pins.setPin(authentication.getName(), request.password(), request.pin(), http.getRemoteAddr());
    }

    @Operation(summary = "Sign out", description = "Revokes the current access token before it expires.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) {
        denylist.revoke(jwt.getId(), jwt.getExpiresAt());
        audit.loggedOut(jwt.getSubject(), jwt.getId());
    }

    private static TooManyRequestsException tooManyAttempts(Duration retryAfter) {
        return new TooManyRequestsException("LOGIN_LOCKED",
                "Too many failed sign-in attempts. Try again later.", retryAfter);
    }

    @Operation(summary = "The signed-in user")
    @GetMapping("/me")
    UserResponse me(JwtAuthenticationToken authentication) {
        AppUser user = findUser(authentication.getName());
        List<String> methods = authentication.getToken().getClaimAsStringList(JwtTokenService.AMR_CLAIM);
        String method = methods == null || methods.isEmpty() ? JwtTokenService.PASSWORD : methods.getFirst();
        return new UserResponse(user.getUsername(), user.getDisplayName(), user.getRole(), user.hasPin(), method);
    }

    private AppUser findUser(String username) {
        return users.findByUsernameIgnoreCase(username).orElseThrow(() -> new ResourceNotFoundException("user", username));
    }
}
