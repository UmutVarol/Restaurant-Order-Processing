package com.umutvarol.restaurant.operations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What goes wrong without anyone seeing it (a legal document not issued, a ticket that did not print, a guest's
 * payment left unfinished) is counted for the monitoring system and reported to managers by the health endpoint,
 * without taking the readiness probe down: the till must keep working while someone looks.
 */
class OperationsHealthIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void silentFailuresAreCountedForMonitoring() throws Exception {
        for (String gauge : new String[] {"sofra.fiscal.unissued", "sofra.fiscal.unissued.oldest", "sofra.print.failed",
                "sofra.print.queued.oldest", "sofra.payments.pending", "sofra.payments.pending.oldest"}) {
            mvc.perform(as(ADMIN, get("/actuator/metrics/{name}", gauge)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(gauge));
        }
        mvc.perform(as(ADMIN, get("/actuator/health")))
                .andExpect(jsonPath("$.components.fiscal.details.unissued").isNumber())
                .andExpect(jsonPath("$.components.printing.details.failedLastHour").isNumber())
                .andExpect(jsonPath("$.components.onlinePayments.details.pending").isNumber());
    }

    /** The alert rules (ops/prometheus/alerts.yml) only work if every Sofra metric they name is really scraped. */
    @Test
    void everyMetricTheAlertRulesUseIsScraped() throws Exception {
        String rules = java.nio.file.Files.readString(java.nio.file.Path.of("ops/prometheus/alerts.yml"));
        String scrape = mvc.perform(as(ADMIN, get("/actuator/prometheus"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.Set<String> used = new java.util.TreeSet<>();
        java.util.regex.Matcher names = java.util.regex.Pattern.compile("\\b(sofra_[a-z_]+|http_server_requests_seconds_count)\\b").matcher(rules);
        while (names.find()) {
            used.add(names.group(1));
        }
        // written by the backup script, not the app
        used.remove("sofra_backup_last_success_timestamp_seconds");
        org.assertj.core.api.Assertions.assertThat(used).isNotEmpty()
                .allSatisfy(name -> org.assertj.core.api.Assertions.assertThat(scrape).as(name).containsPattern("(?m)^" + name + "[{ ]"));
    }

    /** Every reason the payment audit flags something with has its counter from start-up (see PaymentAuditLog). */
    @Test
    void everySuspiciousReasonIsCountedFromStartUp() throws Exception {
        java.util.Set<String> called = new java.util.TreeSet<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher calls = java.util.regex.Pattern.compile("suspicious\\(\"([a-z_]+)\"").matcher(java.nio.file.Files.readString(file));
                while (calls.find()) {
                    called.add(calls.group(1));
                }
            }
        }
        String scrape = mvc.perform(as(ADMIN, get("/actuator/prometheus"))).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(called).isNotEmpty()
                .allSatisfy(reason -> org.assertj.core.api.Assertions.assertThat(scrape).contains("sofra_payments_suspicious_total{reason=\"" + reason + "\"}"));
    }

    @Test
    void aLegalDocumentLeftUnissuedDegradesHealthButNotReadiness() throws Exception {
        long order = placeTakeaway("Ops Check", "Ayran", 1);
        long bill = payInFull(order, "CASH");
        for (int step = 0; step < 3; step++) {
            mvc.perform(as(WAITER, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders/{id}/advance", order)));
        }
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        // the receipt was refused by the device two hours ago and is waiting for its next try
        int changed = jdbc.update("UPDATE fiscal_documents SET status = 'FAILED', created_at = ?, next_attempt_at = ? WHERE bill_id = ?",
                Timestamp.from(longAgo), Timestamp.from(Instant.now().plus(Duration.ofDays(1))), bill);
        org.assertj.core.api.Assertions.assertThat(changed).isEqualTo(1);
        try {
            mvc.perform(as(ADMIN, get("/actuator/health")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.components.fiscal.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.components.fiscal.details.unissued").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.components.fiscal.details.oldestMinutes").value(org.hamcrest.Matchers.greaterThanOrEqualTo(119)));
            // anyone may ask how the app is, but only managers see why
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(as(ADMIN, get("/actuator/metrics/sofra.fiscal.unissued.oldest")))
                    .andExpect(jsonPath("$.measurements[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7140.0)));
        } finally {
            jdbc.update("UPDATE fiscal_documents SET status = 'ISSUED', next_attempt_at = NULL WHERE bill_id = ?", bill);
        }
        mvc.perform(as(ADMIN, get("/actuator/health"))).andExpect(jsonPath("$.components.fiscal.status").value("UP"));
    }
}
