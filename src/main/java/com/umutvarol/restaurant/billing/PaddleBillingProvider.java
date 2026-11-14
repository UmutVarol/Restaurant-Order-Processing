package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.business.Business.Plan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paddle Billing (api.paddle.com): Paddle is the merchant of record, so it charges the card, sends the invoice and
 * pays the sales taxes of the customer's country. Sofra opens a transaction for the plan's price and the number of
 * branches, sends the owner to Paddle's checkout, and learns the outcome from signed webhooks.
 * <p>
 * Written against Paddle's API as documented in 2026; check the request shapes against the sandbox before charging
 * real customers (docs/CLOUD.md).
 */
class PaddleBillingProvider implements BillingProvider {

    static final String NAME = "PADDLE";
    static final String SIGNATURE_HEADER = "paddle-signature";
    /** Older signatures are refused, so a captured webhook cannot be replayed later. */
    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BillingProperties properties;
    private final RestClient http;
    private final Clock clock;

    PaddleBillingProvider(BillingProperties properties, RestClient.Builder http, Clock clock) {
        if (blank(properties.apiKey()) || blank(properties.webhookSecret())) {
            throw new IllegalStateException("Paddle is selected but BILLING_API_KEY and BILLING_WEBHOOK_SECRET are not set.");
        }
        for (Plan plan : Plan.values()) {
            for (BillingInterval interval : BillingInterval.values()) {
                if (blank(properties.prices().get(key(plan, interval)))) {
                    throw new IllegalStateException("Paddle is selected but the price of " + key(plan, interval) + " is not set.");
                }
            }
        }
        this.properties = properties;
        this.clock = clock;
        this.http = http.baseUrl(properties.baseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String checkout(CheckoutRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("price_id", price(request.plan(), request.interval()), "quantity", request.branches())));
        body.put("custom_data", Map.of("business_id", String.valueOf(request.businessId())));
        if (request.customerRef() != null) {
            body.put("customer_id", request.customerRef());
        }
        JsonNode data = call("POST", "/transactions", body);
        String url = data.path("checkout").path("url").asString("");
        if (url.isBlank()) {
            throw new BillingUnavailableException("Paddle returned no checkout page; is a default payment link set?");
        }
        return url;
    }

    @Override
    public Optional<String> portal(String customerRef) {
        JsonNode data = call("POST", "/customers/" + customerRef + "/portal-sessions", Map.of());
        String url = data.path("urls").path("general").path("overview").asString("");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    @Override
    public void changeBranches(String subscriptionRef, Plan plan, BillingInterval interval, int branches) {
        call("PATCH", "/subscriptions/" + subscriptionRef, Map.of(
                "items", List.of(Map.of("price_id", price(plan, interval), "quantity", branches)),
                "proration_billing_mode", "prorated_immediately"));
    }

    @Override
    public Optional<SubscriptionUpdate> parseWebhook(byte[] body, Map<String, String> headers) {
        verify(body, headers.get(SIGNATURE_HEADER));
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (JacksonException ex) {
            throw new InvalidWebhookException("Unreadable webhook body");
        }
        if (!event.path("event_type").asString("").startsWith("subscription.")) {
            return Optional.empty();
        }
        JsonNode data = event.path("data");
        String business = data.path("custom_data").path("business_id").asString("");
        JsonNode item = data.path("items").path(0);
        Optional<PlanPrice> price = plan(item.path("price").path("id").asString(""));
        if (!business.matches("\\d{1,18}") || price.isEmpty()) {
            throw new InvalidWebhookException("A subscription webhook without a business or a known price");
        }
        SubscriptionUpdate.State state = switch (data.path("status").asString("").toLowerCase(Locale.ROOT)) {
            case "active", "trialing" -> SubscriptionUpdate.State.ACTIVE;
            case "past_due" -> SubscriptionUpdate.State.PAST_DUE;
            default -> SubscriptionUpdate.State.CANCELLED;
        };
        String ends = data.path("current_billing_period").path("ends_at").asString("");
        if (instant(ends) == null) {
            ends = "";
        }
        return Optional.of(new SubscriptionUpdate(Long.parseLong(business), data.path("customer_id").asString(""),
                data.path("id").asString(""), price.get().plan(), price.get().interval(), Math.max(1, item.path("quantity").asInt(1)),
                state, ends.isBlank() ? null : Instant.parse(ends), instant(event.path("occurred_at").asString(""))));
    }

    /** {@code Paddle-Signature: ts=<unix seconds>;h1=<hex HMAC-SHA256 of "ts:body">}. */
    void verify(byte[] body, @Nullable String header) {
        if (header == null) {
            throw new InvalidWebhookException("No signature");
        }
        String ts = null;
        String h1 = null;
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("ts")) {
                ts = pair[1];
            } else if (pair.length == 2 && pair[0].equals("h1")) {
                h1 = pair[1];
            }
        }
        if (ts == null || h1 == null || !ts.matches("\\d{1,12}")) {
            throw new InvalidWebhookException("Malformed signature");
        }
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(ts));
        if (Duration.between(signedAt, clock.instant()).abs().compareTo(SIGNATURE_TOLERANCE) > 0) {
            throw new InvalidWebhookException("Signature too old");
        }
        byte[] expected = hmac(ts + ":" + new String(body, StandardCharsets.UTF_8));
        byte[] given;
        try {
            given = HexFormat.of().parseHex(h1);
        } catch (IllegalArgumentException ex) {
            throw new InvalidWebhookException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw new InvalidWebhookException("Signature does not match");
        }
    }

    byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            String response = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return JSON.readTree(response == null ? "{}" : response).path("data");
        } catch (RestClientException | JacksonException ex) {
            throw new BillingUnavailableException("Paddle could not be reached");
        }
    }

    private String price(Plan plan, BillingInterval interval) {
        return properties.prices().get(key(plan, interval));
    }

    private record PlanPrice(Plan plan, BillingInterval interval) {
    }

    private Optional<PlanPrice> plan(String priceId) {
        for (Plan plan : Plan.values()) {
            for (BillingInterval interval : BillingInterval.values()) {
                if (priceId.equals(price(plan, interval))) {
                    return Optional.of(new PlanPrice(plan, interval));
                }
            }
        }
        return Optional.empty();
    }

    private static @Nullable Instant instant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    static String key(Plan plan, BillingInterval interval) {
        return plan.name() + "_" + interval.name();
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
