package com.umutvarol.restaurant.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/** With two-step sign-in required, a manager who has not set it up cannot move money or register tills. */
@TestPropertySource(properties = "restaurant.security.totp.required-for-managers=true")
class TwoFactorRequiredIntegrationTest extends ApiIntegrationTest {

    @Test
    void aManagerWithoutTwoStepSignInIsSentToSetItUp() throws Exception {
        tokenFor(ADMIN); // makes sure the account exists
        // a token issued under this setting (the cached ones in the base class may come from another context)
        String body = mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "admin", "password": "admin-pw"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mfaSetupRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(body, "$.accessToken");
        mvc.perform(json(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, bearer), """
                        {"name": "Till"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_SETUP_REQUIRED"));
    }
}
