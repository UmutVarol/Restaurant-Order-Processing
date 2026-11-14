package com.umutvarol.restaurant.billing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Where the billing provider reports payments, late payments and cancellations. Only signed calls count. */
@Tag(name = "Billing", description = "Plan and subscription (Sofra Cloud)")
@RestController
class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger("billing.audit");

    private final SubscriptionService subscriptions;

    BillingWebhookController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Operation(summary = "Billing provider webhook")
    @PostMapping("/api/v1/public/billing/webhook/{provider}")
    ResponseEntity<Void> webhook(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        try {
            subscriptions.webhook(provider, body, headers);
            return ResponseEntity.ok().build();
        } catch (BillingProvider.InvalidWebhookException ex) {
            log.warn("billing.webhook_refused provider={} reason={}", provider, ex.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
