package com.umutvarol.restaurant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.security.totp.Base32;
import com.umutvarol.restaurant.security.totp.Totp;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

class TwoFactorApiIntegrationTest extends ApiIntegrationTest {

    private static final String MANAGER = "mfa-manager";

    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    void freshManager() {
        users.findByUsernameIgnoreCase(MANAGER).ifPresent(users::delete);
        users.save(new AppUser(MANAGER, encoder.encode("mfa-pw"), "Two Step", Role.ADMIN));
    }

    @Test
    void aManagerTurnsOnTwoStepSignInAndThenNeedsACode() throws Exception {
        String token = login(null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(token, "$.accessToken");

        mvc.perform(get("/api/v1/auth/totp").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(json(post("/api/v1/auth/totp/setup").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"password": "wrong"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
        String setup = mvc.perform(json(post("/api/v1/auth/totp/setup").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"password": "mfa-pw"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/Sofra:mfa-manager?secret=")))
                .andReturn().getResponse().getContentAsString();
        byte[] secret = Base32.decode(JsonPath.read(setup, "$.secret"));
        long step = Totp.step(Instant.now());

        mvc.perform(json(post("/api/v1/auth/totp/enable").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"code": "000000"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_INVALID"));
        String enabled = mvc.perform(json(post("/api/v1/auth/totp/enable").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"code": "%s"}""".formatted(Totp.code(secret, step))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        List<String> recovery = JsonPath.read(enabled, "$.codes");
        assertThat(recovery.getFirst()).matches("[a-z2-9]{5}-[a-z2-9]{5}");

        // the password alone is no longer enough
        login(null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TOTP_REQUIRED"));
        // the code the app showed while enrolling cannot be replayed
        login(Totp.code(secret, step)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TOTP_INVALID"));
        String signedIn = login(Totp.code(secret, step + 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.totpEnabled").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(claims(JsonPath.read(signedIn, "$.accessToken"))).contains("\"amr\":[\"pwd\",\"otp\"]").contains("\"mfa\":true");

        // a recovery code works once
        login(recovery.get(3)).andExpect(status().isOk());
        login(recovery.get(3)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TOTP_INVALID"));
        mvc.perform(get("/api/v1/auth/totp").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(9));

        // turning it off needs the password and a code
        mvc.perform(json(post("/api/v1/auth/totp/disable").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"password": "mfa-pw", "code": "123456"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/auth/totp/disable").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"password": "mfa-pw", "code": "%s"}""".formatted(recovery.get(5))))
                .andExpect(status().isNoContent());
        login(null).andExpect(status().isOk());
    }

    @Test
    void wrongCodesCountTowardsTheLockout() throws Exception {
        String bearer = "Bearer " + JsonPath.read(login(null).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String setup = mvc.perform(json(post("/api/v1/auth/totp/setup").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"password": "mfa-pw"}""")).andReturn().getResponse().getContentAsString();
        byte[] secret = Base32.decode(JsonPath.read(setup, "$.secret"));
        mvc.perform(json(post("/api/v1/auth/totp/enable").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"code": "%s"}""".formatted(Totp.code(secret, Totp.step(Instant.now())))))
                .andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login("00000" + i).andExpect(status().isForbidden());
        }
        login("000009").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
    }

    @Test
    void onlyManagersUseIt() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/auth/totp"))).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions login(String code) throws Exception {
        String body = code == null ? """
                {"username": "%s", "password": "mfa-pw"}""".formatted(MANAGER) : """
                {"username": "%s", "password": "mfa-pw", "code": "%s"}""".formatted(MANAGER, code);
        return mvc.perform(json(post("/api/v1/auth/login"), body));
    }

    private static String claims(String jwt) {
        return new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
    }
}
