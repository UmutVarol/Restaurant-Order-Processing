package com.umutvarol.restaurant.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = {
        "restaurant.security.rate-limit.api-requests-per-minute=10",
        "restaurant.security.rate-limit.login-requests-per-minute=3",
        "restaurant.security.rate-limit.public-requests-per-minute=4",
        "restaurant.realtime.max-subscribers-per-user=1"})
class RateLimitIntegrationTest extends ApiIntegrationTest {

    @Test
    void aClientOverItsBudgetIsToldToWait() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(from("10.0.0.1", get("/api/v1/menu"))).andExpect(status().isOk());
        }
        mvc.perform(from("10.0.0.1", get("/api/v1/menu")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("\\d+")))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // Other clients are not affected.
        mvc.perform(from("10.0.0.2", get("/api/v1/menu"))).andExpect(status().isOk());
    }

    @Test
    void signInAttemptsHaveTheirOwnTighterBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(json(from("10.0.0.3", post("/api/v1/auth/login")), """
                    {"username": "spray-%d", "password": "Summer2026"}""".formatted(i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json(from("10.0.0.3", post("/api/v1/auth/login")), """
                {"username": "spray-9", "password": "Summer2026"}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void pagesThatNeedNoSignInHaveTheirOwnTighterBudget() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(from("10.0.0.6", get("/api/v1/public/pay/{token}", "x".repeat(43)))).andExpect(status().isNotFound());
        }
        mvc.perform(from("10.0.0.6", get("/api/v1/public/pay/{token}", "x".repeat(43))))
                .andExpect(status().isTooManyRequests());
        // Staff screens from the same address still work.
        mvc.perform(from("10.0.0.6", get("/api/v1/menu"))).andExpect(status().isOk());
    }

    @Test
    void oneUserCannotHoldMoreLiveStreamsThanAllowed() throws Exception {
        mvc.perform(from("10.0.0.5", as(CHEF, get("/api/v1/events/orders").accept(MediaType.TEXT_EVENT_STREAM))))
                .andExpect(request().asyncStarted());
        mvc.perform(from("10.0.0.5", as(CHEF, get("/api/v1/events/orders").accept(MediaType.TEXT_EVENT_STREAM))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void staticFilesAreNotRateLimited() throws Exception {
        for (int i = 0; i < 15; i++) {
            mvc.perform(from("10.0.0.4", get("/index.html"))).andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }
}
