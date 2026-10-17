package com.umutvarol.restaurant;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real migrations, demo seeding and the database-heavy endpoints against PostgreSQL — the production
 * database — instead of H2. Skipped automatically where Docker is not available.
 */
@SpringBootTest(properties = {"restaurant.demo.enabled=true", "restaurant.demo.history-days=7"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresCompatibilityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mvc;

    private String adminToken;

    @BeforeEach
    void signIn() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "admin123"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminToken = "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    @Test
    void aggregatesSalesInTheDatabase() throws Exception {
        mvc.perform(get("/api/v1/analytics/dashboard").param("days", "7").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.orders", greaterThan(100)))
                .andExpect(jsonPath("$.topItems", hasSize(5)))
                .andExpect(jsonPath("$.categories", hasSize(4)));
    }

    @Test
    void searchesCaseInsensitivelyIncludingTurkishCharacters() throws Exception {
        mvc.perform(get("/api/v1/orders").param("q", "kadık").param("type", "DELIVERY")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThan(0)));
    }

    @Test
    void placesAndPricesOrders() throws Exception {
        mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TAKEAWAY", "customerName": "Postgres Pınar", "paymentMethod": "CARD",
                                 "couponCode": "TATLI50", "items": [{"menuItemId": 16, "quantity": 2}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pricing.total").value(517.00));
        mvc.perform(get("/api/v1/floor/tables").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("T4")));
    }
}
