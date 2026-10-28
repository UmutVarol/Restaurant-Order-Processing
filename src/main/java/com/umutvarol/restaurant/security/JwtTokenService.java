package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.security.dto.TokenResponse;
import com.umutvarol.restaurant.security.dto.UserResponse;
import com.umutvarol.restaurant.security.totp.TotpProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues signed access tokens carrying the user's role in the {@code roles} claim. Following RFC 8725 and the
 * OWASP JWT cheat sheet, every token is explicitly typed ({@code at+jwt}), names its audience, and has a unique
 * {@code jti} so that it can be revoked on sign-out.
 */
@Service
public class JwtTokenService {

    static final String ROLES_CLAIM = "roles";
    static final String NAME_CLAIM = "name";
    static final String TOKEN_TYPE = "at+jwt";
    /** RFC 8176 authentication method: {@code pwd} for a password, {@code pin} for a PIN on a registered till. */
    static final String AMR_CLAIM = "amr";
    public static final String PASSWORD = "pwd";
    public static final String PIN = "pin";
    public static final String OTP = "otp";
    /** Set when the user has two-step sign-in on: step-up actions then need {@code otp} in {@code amr} too. */
    static final String MFA_CLAIM = "mfa";
    /** Set when a manager still has to turn on two-step sign-in that the restaurant requires. */
    static final String MFA_SETUP_CLAIM = "mfa_setup";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final TotpProperties totp;

    public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock, TotpProperties totp) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.totp = totp;
    }

    public TokenResponse issue(AppUser user) {
        return issue(user, PASSWORD);
    }

    public TokenResponse issue(AppUser user, String method) {
        return issue(user, List.of(method));
    }

    public TokenResponse issue(AppUser user, List<String> methods) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.timeToLive());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(NAME_CLAIM, user.getDisplayName())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .claim(AMR_CLAIM, List.copyOf(methods))
                .claim(MFA_CLAIM, user.isTotpEnabled())
                .claim(MFA_SETUP_CLAIM, TwoFactorService.setupRequired(totp, user))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type(TOKEN_TYPE).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", expiresAt, userResponse(user, methods.getFirst()));
    }

    public UserResponse userResponse(AppUser user, String method) {
        return new UserResponse(user.getUsername(), user.getDisplayName(), user.getRole(), user.hasPin(), method,
                user.isTotpEnabled(), TwoFactorService.setupRequired(totp, user));
    }
}
