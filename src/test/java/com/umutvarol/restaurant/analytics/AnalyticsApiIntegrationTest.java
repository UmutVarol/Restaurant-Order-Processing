package com.umutvarol.restaurant.analytics;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

class AnalyticsApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void managersSeeTheDashboard() throws Exception {
        placeTakeaway("Dashboard Deniz", "Künefe", 3);

        mvc.perform(as(ADMIN, get("/api/v1/analytics/dashboard").param("days", "7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.current.orders", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.daily", hasSize(7)))
                .andExpect(jsonPath("$.hourly", hasSize(24)))
                .andExpect(jsonPath("$.statuses", hasSize(7)))
                .andExpect(jsonPath("$.topItems[*].name").isNotEmpty())
                .andExpect(jsonPath("$.categories[?(@.category == 'DESSERT')].quantity").isNotEmpty());
    }

    @Test
    void rejectsPeriodsOutOfRange() throws Exception {
        mvc.perform(as(ADMIN, get("/api/v1/analytics/dashboard").param("days", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("days"));
    }

    @Test
    void reportsAreForManagersOnly() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/analytics/dashboard"))).andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/analytics/dashboard"))).andExpect(status().isForbidden());
    }
}
