package com.umutvarol.restaurant.channel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Signed platform webhooks, idempotent ingestion, item mapping, and updates back to the platform. */
class ChannelApiIntegrationTest extends ApiIntegrationTest {

    private static final String SECRET = "test-only-channel-secret-0123456789";

    @Test
    void onlyCorrectlySignedFreshDeliveriesAreAccepted() throws Exception {
        String body = order("YS-" + suffix(), "YS-AYRAN-" + suffix(), "70.00", "[]");
        long now = Instant.now().getEpochSecond();
        webhook("yemeksepeti", body, now, "0".repeat(64))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        webhook("yemeksepeti", body, now, null)
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        long old = now - 3600;
        webhook("yemeksepeti", body, old, WebhookSignature.sign(SECRET, old, bytes(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WEBHOOK_EXPIRED"));
        // a body changed after signing does not pass
        webhook("yemeksepeti", body.replace("70.00", "1.00"), now, WebhookSignature.sign(SECRET, now, bytes(body)))
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        // a platform with no secret takes no webhooks at all
        webhook("getir", body, now, WebhookSignature.sign(SECRET, now, bytes(body))).andExpect(status().isNotFound());
        signed("yemeksepeti", "{\"id\": \"\"}").andExpect(jsonPath("$.code").value("WEBHOOK_PAYLOAD_INVALID"));
    }

    @Test
    void aMappedOrderIsPlacedOncePaidAndFlaggedWhenPricesDiffer() throws Exception {
        String item = "YS-ADANA-" + suffix();
        map("yemeksepeti", item, "Adana Kebap (Porsiyon)", menuItemId("Adana Kebap"));
        String id = "YS-" + suffix();
        String body = order(id, item, "500.00", "[\"Acılık: Az acılı\"]");

        String first = signed("yemeksepeti", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.priceDiffers").value(true))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(first, "$.orderId")).longValue();
        long channelOrderId = ((Number) JsonPath.read(first, "$.id")).longValue();

        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", orderId)))
                .andExpect(jsonPath("$.channel").value("YEMEKSEPETI"))
                .andExpect(jsonPath("$.type").value("TAKEAWAY"))
                .andExpect(jsonPath("$.customerName").value("Yemeksepeti · Deniz K."))
                .andExpect(jsonPath("$.lines[0].options[0].choice").value("Az acılı"));
        mvc.perform(as(WAITER, get("/api/v1/bills/by-order/{id}", orderId))).andExpect(jsonPath("$.balance").value(0.0));

        // the platform delivers the same order again: nothing new happens
        signed("yemeksepeti", body)
                .andExpect(jsonPath("$.id").value(channelOrderId))
                .andExpect(jsonPath("$.orderId").value(orderId));
        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", "Deniz K.").param("size", "100")))
                .andExpect(jsonPath("$.content[?(@.note =~ /.*%s.*/)]".formatted(id)).value(org.hamcrest.Matchers.hasSize(1)));

        // the kitchen starts: the platform hears it
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/advance", orderId))).andExpect(jsonPath("$.status").value("PREPARING"));
        mvc.perform(as(ADMIN, get("/api/v1/channels/orders")))
                .andExpect(jsonPath("$[?(@.id == %d)].syncedStatus".formatted(channelOrderId)).value("ACCEPTED"));
    }

    @Test
    void anUnmappedItemStopsTheOrderUntilAManagerMapsItOrTurnsItDown() throws Exception {
        String item = "YS-NEW-" + suffix();
        // ayran is 70 before tax: the guest paid 77 on the platform
        String waiting = signed("yemeksepeti", order("YS-" + suffix(), item, "77.00", "[]"))
                .andExpect(jsonPath("$.status").value("ATTENTION"))
                .andExpect(jsonPath("$.problemCode").value("CHANNEL_ITEM_UNMAPPED"))
                .andExpect(jsonPath("$.unmapped[0].externalId").value(item))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(waiting, "$.id")).longValue();

        map("yemeksepeti", item, "Ayran 300 ml", menuItemId("Ayran"));
        mvc.perform(as(ADMIN, post("/api/v1/channels/orders/{id}/retry", id)))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.priceDiffers").value(false));

        String other = signed("yemeksepeti", order("YS-" + suffix(), "YS-GONE-" + suffix(), "70.00", "[]"))
                .andReturn().getResponse().getContentAsString();
        long otherId = ((Number) JsonPath.read(other, "$.id")).longValue();
        mvc.perform(as(ADMIN, post("/api/v1/channels/orders/{id}/reject", otherId)))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(as(ADMIN, post("/api/v1/channels/orders/{id}/retry", otherId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_ORDER_NOT_WAITING"));
        mvc.perform(as(ADMIN, get("/api/v1/channels/orders")))
                .andExpect(jsonPath("$[?(@.id == %d)].syncedStatus".formatted(otherId)).value("REJECTED"));
    }

    @Test
    void simulatedPlatformsMakeUpOrdersAndHearAboutSoldOutDishes() throws Exception {
        long lahmacun = menuItemId("Lahmacun");
        map("getir", "GTR-LAH-" + suffix(), "Lahmacun", lahmacun);
        mvc.perform(json(as(ADMIN, post("/api/v1/channels/getir/simulate")), "{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("GETIR"))
                .andExpect(jsonPath("$.status").value("PLACED"));
        mvc.perform(json(as(ADMIN, post("/api/v1/channels/yemeksepeti/simulate")), "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_SIMULATED"));

        mvc.perform(json(as(ADMIN, patch("/api/v1/menu/{id}/availability", lahmacun)), "{\"available\": false}"))
                .andExpect(status().isOk());
        try {
            mvc.perform(as(ADMIN, get("/api/v1/channels")))
                    .andExpect(jsonPath("$[?(@.channel == 'GETIR')].mode").value("SIMULATED"))
                    .andExpect(jsonPath("$[?(@.channel == 'YEMEKSEPETI')].mode").value("CONNECTED"))
                    .andExpect(jsonPath("$[?(@.channel == 'GETIR')].recentUpdates[0].value").value("OFF"));
        } finally {
            mvc.perform(json(as(ADMIN, patch("/api/v1/menu/{id}/availability", lahmacun)), "{\"available\": true}"));
        }

        mvc.perform(as(WAITER, get("/api/v1/channels"))).andExpect(status().isForbidden());
    }

    private void map(String channel, String externalId, String name, long menuItemId) throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/channels/{channel}/mappings/{id}", channel, externalId)), """
                        {"externalName": "%s", "menuItemId": %d}""".formatted(name, menuItemId)))
                .andExpect(status().isOk());
    }

    private ResultActions signed(String channel, String body) throws Exception {
        long now = Instant.now().getEpochSecond();
        return webhook(channel, body, now, WebhookSignature.sign(SECRET, now, bytes(body)));
    }

    private ResultActions webhook(String channel, String body, long timestamp, String signature) throws Exception {
        var request = post("/api/v1/channels/{channel}/webhook", channel).contentType(MediaType.APPLICATION_JSON)
                .content(bytes(body)).header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp));
        if (signature != null) {
            request.header(WebhookSignature.SIGNATURE_HEADER, signature);
        }
        return mvc.perform(request);
    }

    private static String order(String id, String item, String unitPrice, String options) {
        return """
                {"id": "%s", "customer": {"name": "Deniz K.", "phone": "+90 5** *** 12 34", "address": "Moda"},
                 "items": [{"id": "%s", "name": "Platform item", "quantity": 1, "unitPrice": %s, "options": %s}],
                 "total": %s, "note": "Zile basmayın"}""".formatted(id, item, unitPrice, options, unitPrice);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
