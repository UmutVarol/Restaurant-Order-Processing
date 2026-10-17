package com.umutvarol.restaurant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/** Cross-cutting endpoints: health, metrics, API documentation, coupons and security headers. */
class PlatformIntegrationTest extends ApiIntegrationTest {

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void metricsAreForManagers() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(as(WAITER, get("/actuator/prometheus"))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, get("/actuator/metrics/sofra.orders.active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("sofra.orders.active"));
    }

    @Test
    void publishesAnOpenApiDescription() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value(containsString("Sofra")))
                .andExpect(jsonPath("$.paths['/api/v1/orders']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void listsRedeemableCouponsOnly() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/coupons")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(hasItem("HOSGELDIN")))
                .andExpect(jsonPath("$[?(@.code == 'YAZ2025')]").isEmpty());
    }

    @Test
    void sendsSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/menu"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/internal/secrets")).andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheWebApp() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sofra")));
    }
}
