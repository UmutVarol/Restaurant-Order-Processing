package com.umutvarol.restaurant.floor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

class FloorApiIntegrationTest extends ApiIntegrationTest {

    private static final String TABLE = "$[?(@.code == 'T4')]";

    @Test
    void aTableStaysOccupiedUntilTheBillIsClosed() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath(TABLE + ".occupied").value(false));

        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Terrace Guest", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(tableId("T4"), menuItemId("Demlik Çay"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(as(CHEF, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(TABLE + ".occupied").value(true))
                .andExpect(jsonPath(TABLE + ".openTotal").value(99.00))
                .andExpect(jsonPath(TABLE + ".openOrders[0].id").value((int) id))
                .andExpect(jsonPath(TABLE + ".openOrders[0].itemCount").value(2));

        for (int step = 0; step < 3; step++) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", id))).andExpect(status().isOk());
        }
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(TABLE + ".occupied").value(true))
                .andExpect(jsonPath(TABLE + ".openOrders[0].status").value("SERVED"));

        // Paying the bill in full completes the served order and frees the table.
        payInFull(id, "CASH");
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", id)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(TABLE + ".occupied").value(false));
    }
}
