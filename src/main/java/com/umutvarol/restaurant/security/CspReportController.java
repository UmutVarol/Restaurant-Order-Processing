package com.umutvarol.restaurant.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Content-Security-Policy violation reports from browsers (both the older {@code report-uri} format and
 * the Reporting API). A violation on a real user's screen means either a bug or someone injecting script into the
 * page, for instance a skimmer on the payment page, so each one is logged to {@code security.csp} and counted in
 * {@code sofra.security.csp_violation} for alerting. Bodies are capped by the request size limit and truncated
 * before logging; anyone can send these, so they are never trusted beyond that.
 */
@Tag(name = "Security", description = "Browser security reports")
@RestController
class CspReportController {

    private static final Logger log = LoggerFactory.getLogger("security.csp");
    private static final int MAX_LOGGED = 600;

    private final MeterRegistry meters;

    CspReportController(MeterRegistry meters) {
        this.meters = meters;
    }

    @Operation(summary = "Content-Security-Policy violation report", description = "Sent by browsers, not by people.")
    @PostMapping(value = "/api/v1/csp-reports",
            consumes = {"application/csp-report", "application/reports+json", "application/json"})
    ResponseEntity<Void> report(@RequestBody(required = false) String body) {
        meters.counter("sofra.security.csp_violation").increment();
        String text = body == null ? "" : body.replaceAll("\\p{Cntrl}", " ");
        log.warn("csp.violation {}", text.length() > MAX_LOGGED ? text.substring(0, MAX_LOGGED) + "…" : text);
        return ResponseEntity.noContent().build();
    }
}
