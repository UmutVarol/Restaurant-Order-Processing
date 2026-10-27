package com.umutvarol.restaurant.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Refunds and closing the day need a recent sign-in; here "recent" is made impossibly short. */
@TestPropertySource(properties = "restaurant.security.step-up-window=0s")
class StepUpAuthenticationIntegrationTest extends ApiIntegrationTest {

    @Test
    void refundsAndClosingTheDayAskForTheManagersPasswordAgain() throws Exception {
        String token = freshAdminToken();
        mvc.perform(post("/api/v1/payments/1/refunds").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"amount": 1, "reason": "Test"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        mvc.perform(post("/api/v1/payments/day/close").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"date": "2026-01-01", "countedCash": 0}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    private String freshAdminToken() throws Exception {
        tokenFor(ADMIN); // makes sure the account exists
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username": "admin", "password": "admin-pw"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Thread.sleep(1100); // tokens carry whole seconds: make it at least a second old
        return JsonPath.read(body, "$.accessToken");
    }
}
