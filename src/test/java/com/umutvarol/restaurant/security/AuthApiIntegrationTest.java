package com.umutvarol.restaurant.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AuthApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void signingInReturnsATokenAndTheStaffProfile() throws Exception {
        mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "WAITER", "password": "waiter-pw"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(WAITER))
                .andExpect(jsonPath("$.user.role").value("WAITER"));

        mvc.perform(as(CHEF, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test Chef"))
                .andExpect(jsonPath("$.role").value("KITCHEN"));
    }

    @Test
    void wrongPasswordsAreRejectedWithoutRevealingWhichPartWasWrong() throws Exception {
        mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "admin", "password": "guess"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "nobody", "password": "guess"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void forgedTokensAreRejected() throws Exception {
        String forged = tokenFor(ADMIN).substring(0, tokenFor(ADMIN).lastIndexOf('.') + 1) + "c2lnbmF0dXJl";

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        Instant issuedAt = Instant.now().minus(10, ChronoUnit.HOURS);
        String expired = mint(JwtClaimsSet.builder().issuer("sofra").subject(ADMIN)
                .issuedAt(issuedAt).expiresAt(issuedAt.plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of("ADMIN")).build());

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokensFromAnotherIssuerAreRejected() throws Exception {
        String foreign = mint(JwtClaimsSet.builder().issuer("someone-else").subject(ADMIN)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of("ADMIN")).build());

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    private String mint(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
    }
}
