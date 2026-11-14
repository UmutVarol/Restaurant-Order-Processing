package com.umutvarol.restaurant.floor;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TableAdminApiIntegrationTest extends ApiIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger(500);

    @Test
    void aManagerAddsRenumbersAndRemovesATable() throws Exception {
        String code = "T" + NUMBERS.incrementAndGet();
        String created = mvc.perform(as(ADMIN, json(post("/api/v1/tables"), """
                        {"code": "%s", "seats": 4, "zone": "TERRACE"}""".formatted(code))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone").value("TERRACE"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        mvc.perform(as(WAITER, get("/api/v1/floor/tables"))).andExpect(jsonPath("$[*].code", hasItem(code)));

        mvc.perform(as(ADMIN, json(post("/api/v1/tables"), """
                        {"code": "%s", "seats": 2, "zone": "INDOOR"}""".formatted(code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_CODE_TAKEN"));

        String renamed = code + "B";
        mvc.perform(as(ADMIN, json(put("/api/v1/tables/{id}", id), """
                        {"code": "%s", "seats": 6, "zone": "WINDOW"}""".formatted(renamed))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(renamed))
                .andExpect(jsonPath("$.seats").value(6));

        // guests at the table: it stays
        String order = mvc.perform(as(WAITER, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "Masa", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(id, menuItemId("Ayran")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        mvc.perform(as(ADMIN, delete("/api/v1/tables/{id}", id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_IN_USE"));
        mvc.perform(as(WAITER, json(post("/api/v1/orders/{id}/cancel", orderId), """
                        {"reason": "Test finished"}"""))).andExpect(status().isOk());

        mvc.perform(as(ADMIN, delete("/api/v1/tables/{id}", id))).andExpect(status().isNoContent());
        mvc.perform(as(WAITER, get("/api/v1/floor/tables"))).andExpect(jsonPath("$[*].code", not(hasItem(renamed))));
        // a removed table takes no new orders
        mvc.perform(as(WAITER, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "Masa", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(id, menuItemId("Ayran")))))
                .andExpect(status().isUnprocessableContent());

        // adding its number again brings it back, history and all
        mvc.perform(as(ADMIN, json(post("/api/v1/tables"), """
                        {"code": "%s", "seats": 2, "zone": "INDOOR"}""".formatted(renamed))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value((int) id));
    }

    @Test
    void onlyManagersChangeTheDiningRoom() throws Exception {
        mvc.perform(as(WAITER, json(post("/api/v1/tables"), """
                        {"code": "W1", "seats": 2, "zone": "INDOOR"}"""))).andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/tables"))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, json(post("/api/v1/tables"), """
                        {"code": "bad code!", "seats": 2, "zone": "INDOOR"}"""))).andExpect(status().isBadRequest());
    }
}
