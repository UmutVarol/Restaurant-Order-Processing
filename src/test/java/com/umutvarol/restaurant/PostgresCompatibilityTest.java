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
                // two künefe at 286 (VAT included) less the 50 lira coupon
                .andExpect(jsonPath("$.pricing.total").value(522.00));
        mvc.perform(get("/api/v1/floor/tables").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("12")));
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    /**
     * The second wall: plain SQL, past Hibernate's tenant filter, still sees only the business of its transaction,
     * and every table that belongs to a business is covered.
     */
    @Test
    void rowLevelSecurityKeepsEachBusinessToItsOwnRows() {
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        Integer own = com.umutvarol.restaurant.business.BusinessContext.call(1L,
                () -> tx.execute(status -> jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)));
        Integer other = com.umutvarol.restaurant.business.BusinessContext.call(999L,
                () -> tx.execute(status -> jdbc.queryForObject("SELECT count(*) FROM orders WHERE business_id = 1", Integer.class)));
        org.assertj.core.api.Assertions.assertThat(own).isGreaterThan(0);
        org.assertj.core.api.Assertions.assertThat(other).isZero();
        Integer changed = com.umutvarol.restaurant.business.BusinessContext.call(999L,
                () -> tx.execute(status -> jdbc.update("UPDATE menu_items SET price = 1 WHERE business_id = 1")));
        org.assertj.core.api.Assertions.assertThat(changed).isZero();

        java.util.List<String> unprotected = jdbc.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                JOIN pg_class k ON k.relname = c.table_name
                WHERE c.column_name = 'business_id' AND c.table_schema = current_schema()
                  AND NOT (k.relrowsecurity AND k.relforcerowsecurity)""", String.class);
        org.assertj.core.api.Assertions.assertThat(unprotected).isEmpty();
    }
}
