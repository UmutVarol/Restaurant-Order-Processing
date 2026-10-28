package com.umutvarol.restaurant.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A till that queued an order offline may send it more than once; it must be placed exactly once. */
class OrderIdempotencyApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void aRetryWithTheSameKeyReturnsTheOrderAlreadyPlaced() throws Exception {
        String key = UUID.randomUUID().toString();
        String guest = "Offline " + key.substring(0, 8);
        String body = """
                {"type": "TAKEAWAY", "customerName": "%s", "paymentMethod": "CASH",
                 "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(guest, menuItemId("Ayran"));

        String first = mvc.perform(json(as(WAITER, post("/api/v1/orders")), body).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(first, "$.id")).longValue();

        mvc.perform(json(as(WAITER, post("/api/v1/orders")), body).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", guest)))
                .andExpect(jsonPath("$.totalElements").value(1));

        // the key is not a way to read someone else's order
        mvc.perform(json(as(ADMIN, post("/api/v1/orders")), body).header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), body).header("Idempotency-Key", "bad key!"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        // without a key every request is a new order, as before
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), body)).andExpect(status().isCreated());
        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", guest)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
