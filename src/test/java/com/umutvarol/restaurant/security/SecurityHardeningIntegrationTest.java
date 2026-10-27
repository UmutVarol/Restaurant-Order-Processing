package com.umutvarol.restaurant.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Abuse protection and token hardening, checked end to end against the real filter chain. */
class SecurityHardeningIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void repeatedFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        createUser("lock-target", Role.WAITER);
        for (int i = 0; i < 4; i++) {
            login("lock-target", "wrong").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }
        login("lock-target", "wrong")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));

        login("lock-target", "lock-target-pw")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
    }

    @Test
    void disabledAccountsGetTheSameAnswerAsAWrongPassword() throws Exception {
        createUser("former-staff", Role.WAITER);
        jdbc.update("UPDATE app_users SET enabled = FALSE WHERE username = ?", "former-staff");

        login("former-staff", "former-staff-pw")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    @Test
    void signingOutRevokesTheToken() throws Exception {
        String token = JsonPath.read(login(CHEF, CHEF + "-pw").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void issuedTokensAreTypedAndCarryAnAudienceAndAnId() throws Exception {
        String token = JsonPath.read(login(WAITER, WAITER + "-pw").andReturn().getResponse().getContentAsString(), "$.accessToken");
        String[] parts = token.split("\\.");
        String header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));

        org.assertj.core.api.Assertions.assertThat(header).contains("\"typ\":\"at+jwt\"").contains("\"alg\":\"HS256\"");
        org.assertj.core.api.Assertions.assertThat(payload).contains("\"aud\":\"sofra-api\"").contains("\"jti\":\"");
    }

    @Test
    void tokensForAnotherAudienceOrWithoutExplicitTypeAreRejected() throws Exception {
        JwtClaimsSet.Builder valid = JwtClaimsSet.builder().id(UUID.randomUUID().toString()).issuer("sofra").subject(ADMIN)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).claim("roles", List.of("ADMIN"));

        String otherAudience = mint("at+jwt", valid.audience(List.of("billing-api")).build());
        String untyped = mint("JWT", valid.audience(List.of("sofra-api")).build());
        String noId = mint("at+jwt", JwtClaimsSet.builder().issuer("sofra").subject(ADMIN).audience(List.of("sofra-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).claim("roles", List.of("ADMIN")).build());
        String good = mint("at+jwt", valid.audience(List.of("sofra-api")).build());

        for (String token : List.of(otherAudience, untyped, noId)) {
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/analytics/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + good))
                .andExpect(status().isOk());
    }

    @Test
    void responsesCarryBrowserHardeningHeaders() throws Exception {
        mvc.perform(get("/api/v1/menu"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void theAppPageAllowsOnlyItsOwnOriginAndEnforcesTrustedTypes() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Content-Security-Policy", containsString("require-trusted-types-for 'script'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(header().string("Content-Security-Policy", not(containsString("https:"))))
                .andExpect(header().string("Reporting-Endpoints", containsString("/api/v1/csp-reports")));
        // Swagger UI gets its own, looser policy, so the app's stays strict.
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(header().string("Content-Security-Policy", containsString("'unsafe-inline'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("trusted-types"))));
    }

    @Test
    void browsersCanReportPolicyViolationsWithoutSigningIn() throws Exception {
        mvc.perform(post("/api/v1/csp-reports").contentType("application/csp-report").content("""
                        {"csp-report": {"document-uri": "https://sofra.example/", "violated-directive": "script-src",
                         "blocked-uri": "https://evil.example/skimmer.js"}}"""))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/csp-reports").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void publishesASecurityContact() throws Exception {
        mvc.perform(get("/.well-known/security.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Contact: https://github.com/UmutVarol/Restaurant-Order-Processing/security")))
                .andExpect(content().string(containsString("Expires: ")));
    }

    @Test
    void oversizedBodiesAreRefusedWith413() throws Exception {
        String huge = "{\"type\":\"TAKEAWAY\",\"items\":[],\"note\":\"" + "x".repeat(70_000) + "\"}";
        mvc.perform(json(as(WAITER, post("/api/v1/orders/quote")), huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mvc.perform(json(post("/api/v1/auth/login"), """
                {"username": "%s", "password": "%s"}""".formatted(username, password)));
    }

    private void createUser(String username, Role role) {
        if (users.findByUsernameIgnoreCase(username).isEmpty()) {
            users.save(new AppUser(username, passwordEncoder.encode(username + "-pw"), username, role));
        }
    }

    private String mint(String type, JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type(type).build(), claims))
                .getTokenValue();
    }
}
