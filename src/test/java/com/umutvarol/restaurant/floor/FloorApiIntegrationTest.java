package com.umutvarol.restaurant.floor;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

class FloorApiIntegrationTest extends ApiIntegrationTest {

    private static final String TABLE = "$[?(@.code == '12')]";

    @Test
    void aTableStaysOccupiedUntilTheBillIsClosed() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(status().isOk())
                // the twelve seeded tables, numbered from 1 (other tests may add their own)
                .andExpect(jsonPath("$[*].code", hasItems("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")))
                .andExpect(jsonPath(TABLE + ".occupied").value(false));

        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Terrace Guest", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(tableId("12"), menuItemId("Demlik Çay"))))
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

    @Test
    void aTableWhoseGuestsPaidWaitsToBeClearedUntilAWaiterOrNewGuestsClearIt() throws Exception {
        String table = newTable();
        String row = "$[?(@.code == '%s')]".formatted(table);
        servePayAndComplete(table);
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(row + ".occupied").value(false))
                .andExpect(jsonPath(row + ".needsClearingSince").isNotEmpty());

        long id = tableId(table);
        mvc.perform(as(CHEF, post("/api/v1/floor/tables/{id}/cleared", id))).andExpect(status().isForbidden());
        mvc.perform(as(WAITER, post("/api/v1/floor/tables/{id}/cleared", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(table))
                .andExpect(jsonPath("$.needsClearingSince").doesNotExist());

        // new guests sitting down clear it too
        servePayAndComplete(table);
        long next = placeDineIn(table);
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(row + ".occupied").value(true))
                .andExpect(jsonPath(row + ".needsClearingSince").value(contains(nullValue())));
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", next)), """
                {"reason": "Test finished"}""")).andExpect(status().isOk());
    }

    private void servePayAndComplete(String table) throws Exception {
        long id = placeDineIn(table);
        for (int step = 0; step < 3; step++) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", id))).andExpect(status().isOk());
        }
        payInFull(id, "CASH");
    }

    private long placeDineIn(String table) throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Clearing", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(tableId(table), menuItemId("Ayran"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }
}
