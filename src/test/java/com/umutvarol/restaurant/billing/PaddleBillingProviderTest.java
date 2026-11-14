package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.business.Business.Plan;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Paddle's webhooks are believed only with a fresh, matching signature, and mean what Sofra thinks they mean. */
class PaddleBillingProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final PaddleBillingProvider paddle = new PaddleBillingProvider(properties(), RestClient.builder(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aSignedSubscriptionWebhookBecomesAnUpdate() {
        byte[] body = """
                {"event_type": "subscription.updated", "occurred_at": "2026-11-14T11:59:00Z", "data": {"id": "sub_1", "status": "past_due", "customer_id": "ctm_1",
                 "custom_data": {"business_id": "42"}, "items": [{"price": {"id": "pri_chain_y"}, "quantity": 4}],
                 "current_billing_period": {"ends_at": "2027-11-14T12:00:00Z"}}}""".getBytes(StandardCharsets.UTF_8);

        var update = paddle.parseWebhook(body, Map.of(PaddleBillingProvider.SIGNATURE_HEADER, sign(NOW, body))).orElseThrow();

        assertThat(update.businessId()).isEqualTo(42);
        assertThat(update.plan()).isEqualTo(Plan.CHAIN);
        assertThat(update.interval()).isEqualTo(BillingInterval.YEARLY);
        assertThat(update.branches()).isEqualTo(4);
        assertThat(update.state()).isEqualTo(BillingProvider.SubscriptionUpdate.State.PAST_DUE);
        assertThat(update.periodEndsAt()).isEqualTo(Instant.parse("2027-11-14T12:00:00Z"));
        assertThat(update.occurredAt()).isEqualTo(Instant.parse("2026-11-14T11:59:00Z"));
    }

    @Test
    void otherEventsAreAcknowledgedAndIgnored() {
        byte[] body = "{\"event_type\": \"transaction.completed\", \"data\": {}}".getBytes(StandardCharsets.UTF_8);
        assertThat(paddle.parseWebhook(body, Map.of(PaddleBillingProvider.SIGNATURE_HEADER, sign(NOW, body)))).isEmpty();
    }

    @Test
    void aForgedStaleOrMissingSignatureIsRefused() {
        byte[] body = "{\"event_type\": \"subscription.canceled\", \"data\": {}}".getBytes(StandardCharsets.UTF_8);
        String good = sign(NOW, body);
        assertThatThrownBy(() -> paddle.parseWebhook(body, Map.of()))
                .isInstanceOf(BillingProvider.InvalidWebhookException.class);
        assertThatThrownBy(() -> paddle.parseWebhook(body, Map.of(PaddleBillingProvider.SIGNATURE_HEADER, good.replace("h1=", "h1=00"))))
                .isInstanceOf(BillingProvider.InvalidWebhookException.class);
        assertThatThrownBy(() -> paddle.parseWebhook("{}".getBytes(StandardCharsets.UTF_8), Map.of(PaddleBillingProvider.SIGNATURE_HEADER, good)))
                .isInstanceOf(BillingProvider.InvalidWebhookException.class);
        assertThatThrownBy(() -> paddle.parseWebhook(body, Map.of(PaddleBillingProvider.SIGNATURE_HEADER, sign(NOW.minusSeconds(600), body))))
                .hasMessageContaining("too old");
    }

    @Test
    void itRefusesToStartWithoutKeysOrPrices() {
        BillingProperties missing = new BillingProperties(BillingProperties.Provider.PADDLE, 14, "key", "secret",
                URI.create("https://sandbox-api.paddle.com"), Map.of());
        assertThatThrownBy(() -> new PaddleBillingProvider(missing, RestClient.builder(), Clock.systemUTC()))
                .hasMessageContaining("price");
        assertThat(properties().toString()).doesNotContain("secret-key", "whsec");
    }

    private String sign(Instant at, byte[] body) {
        String ts = String.valueOf(at.getEpochSecond());
        return "ts=" + ts + ";h1=" + HexFormat.of().formatHex(paddle.hmac(ts + ":" + new String(body, StandardCharsets.UTF_8)));
    }

    private static BillingProperties properties() {
        Map<String, String> prices = new HashMap<>();
        for (Plan plan : Plan.values()) {
            prices.put(plan.name() + "_MONTHLY", "pri_" + plan.name().toLowerCase() + "_m");
            prices.put(plan.name() + "_YEARLY", "pri_" + plan.name().toLowerCase() + "_y");
        }
        return new BillingProperties(BillingProperties.Provider.PADDLE, 14, "secret-key", "whsec_test",
                URI.create("https://sandbox-api.paddle.com"), prices);
    }
}
