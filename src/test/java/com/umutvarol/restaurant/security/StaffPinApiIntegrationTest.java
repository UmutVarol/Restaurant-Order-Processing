package com.umutvarol.restaurant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

class StaffPinApiIntegrationTest extends ApiIntegrationTest {

    private static final String DEVICE = "X-Device-Token";

    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    @Test
    void staffSwitchWithAPinOnlyOnARegisteredTill() throws Exception {
        String device = registerDevice("Bar till");

        mvc.perform(json(as(WAITER, put("/api/v1/auth/pin")), """
                        {"password": "wrong", "pin": "4826"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
        mvc.perform(json(as(WAITER, put("/api/v1/auth/pin")), """
                        {"password": "waiter-pw", "pin": "1234"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PIN_TOO_SIMPLE"));
        mvc.perform(json(as(WAITER, put("/api/v1/auth/pin")), """
                        {"password": "waiter-pw", "pin": "48a6"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(WAITER, put("/api/v1/auth/pin")), """
                        {"password": "waiter-pw", "pin": "4826"}"""))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/pin-staff"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_REGISTERED"));
        mvc.perform(get("/api/v1/auth/pin-staff").header(DEVICE, device))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'waiter')].displayName").value("Test Waiter"));

        mvc.perform(json(post("/api/v1/auth/pin-login"), """
                        {"username": "waiter", "pin": "4826"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_REGISTERED"));
        mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, "made-up-token"), """
                        {"username": "waiter", "pin": "4826"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "waiter", "pin": "4827"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        String body = mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "waiter", "pin": "4826"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.method").value("pin"))
                .andExpect(jsonPath("$.user.hasPin").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("waiter"))
                .andExpect(jsonPath("$.method").value("pin"));
    }

    @Test
    void aPinSessionNeverMovesMoneyAndARevokedTillStopsAtOnce() throws Exception {
        String device = registerDevice("Front till");
        mvc.perform(json(as(ADMIN, put("/api/v1/auth/pin")), """
                        {"password": "admin-pw", "pin": "7302"}"""))
                .andExpect(status().isNoContent());
        String body = mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "admin", "pin": "7302"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pinToken = JsonPath.read(body, "$.accessToken");

        // signed in seconds ago, but with a PIN: refunds still ask for the password
        mvc.perform(json(post("/api/v1/payments/1/refunds").header(HttpHeaders.AUTHORIZATION, "Bearer " + pinToken), """
                        {"amount": 1, "reason": "Test"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        // and neither can it register a till of its own
        mvc.perform(json(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + pinToken), """
                        {"name": "Sneaky"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        List<Number> ids = JsonPath.read(mvc.perform(as(ADMIN, get("/api/v1/devices")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$[?(@.name == 'Front till')].id");
        long id = ids.getFirst().longValue();
        mvc.perform(as(ADMIN, delete("/api/v1/devices/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "admin", "pin": "7302"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_REGISTERED"));
    }

    @Test
    void onlyManagersRegisterDevices() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/devices")), """
                        {"name": "Mine"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/devices"))).andExpect(status().isForbidden());
    }

    @Test
    void guessingAPinLocksItAndRepeatedLockoutsClearIt() throws Exception {
        String device = registerDevice("Patio till");
        AppUser user = users.findByUsernameIgnoreCase("pinguess")
                .orElseGet(() -> users.save(new AppUser("pinguess", encoder.encode("pinguess-pw"), "Guess Target", Role.WAITER)));
        user.setPinHash(encoder.encode("5819"));
        users.save(user);
        for (int i = 0; i < 4; i++) {
            mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                            {"username": "pinguess", "pin": "000%d"}""".formatted(i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "pinguess", "pin": "0009"}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
        // even the right PIN waits out the lockout
        mvc.perform(json(post("/api/v1/auth/pin-login").header(DEVICE, device), """
                        {"username": "pinguess", "pin": "5819"}"""))
                .andExpect(status().isTooManyRequests());
        assertThat(users.findByUsernameIgnoreCase("pinguess").orElseThrow().hasPin()).isTrue();
    }

    @Test
    void aPinIsClearedAfterThreeLockoutsInARow() {
        AppUser user = new AppUser("x", "hash", "X", Role.WAITER);
        user.setPinHash("pin");
        assertThat(user.pinLockedOut(StaffPinService.PIN_LOCKOUT_LIMIT)).isFalse();
        user.pinAccepted();
        assertThat(user.pinLockedOut(StaffPinService.PIN_LOCKOUT_LIMIT)).isFalse();
        assertThat(user.pinLockedOut(StaffPinService.PIN_LOCKOUT_LIMIT)).isFalse();
        assertThat(user.pinLockedOut(StaffPinService.PIN_LOCKOUT_LIMIT)).isTrue();
        assertThat(user.hasPin()).isFalse();
    }

    @Test
    void obviousPinsAreRefused() {
        for (String pin : new String[] {"0000", "1234", "9876", "1212", "123456", "656565"}) {
            assertThatThrownBy(() -> StaffPinService.requireStrong(pin)).as(pin).isInstanceOf(BusinessRuleException.class);
        }
        StaffPinService.requireStrong("4826");
        StaffPinService.requireStrong("135792");
    }

    private String registerDevice(String name) throws Exception {
        String body = mvc.perform(json(as(ADMIN, post("/api/v1/devices")), """
                        {"name": "%s"}""".formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.name").value(name))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
