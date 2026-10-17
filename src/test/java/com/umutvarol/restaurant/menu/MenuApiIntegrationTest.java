package com.umutvarol.restaurant.menu;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class MenuApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void anyoneCanBrowseTheMenu() throws Exception {
        mvc.perform(get("/api/v1/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(26))))
                .andExpect(jsonPath("$[0].category").value("STARTER"))
                .andExpect(jsonPath("$[*].name").value(hasItem("Mercimek Çorbası")));
    }

    @Test
    void filtersByCategoryAndText() throws Exception {
        mvc.perform(get("/api/v1/menu").param("category", "DESSERT"))
                .andExpect(jsonPath("$[*].category").value(everyItem(is("DESSERT"))))
                .andExpect(jsonPath("$[*].name").value(hasItem("Künefe")));
        mvc.perform(get("/api/v1/menu").param("q", "PISTACHIO"))
                .andExpect(jsonPath("$[*].name").value(hasItem("Fıstıklı Baklava")));
    }

    @Test
    void managersMaintainTheMenu() throws Exception {
        String created = mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": "Testi Kebabı", "description": "Slow-cooked in a sealed clay pot",
                         "category": "MAIN", "price": 690.5, "icon": "🏺", "prepTimeMinutes": 25,
                         "popular": false, "tags": ["GLUTEN_FREE"]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.price").value(690.50))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.tags[0]").value("GLUTEN_FREE"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": "testi kebabı", "category": "MAIN", "price": 1}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_ITEM_NAME_TAKEN"));

        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", id)), """
                        {"name": "Testi Kebabı", "description": "Cracked open at your table", "category": "MAIN",
                         "price": 720, "icon": "🏺", "prepTimeMinutes": 25, "popular": true, "tags": []}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(720.00))
                .andExpect(jsonPath("$.popular").value(true))
                .andExpect(jsonPath("$.tags", hasSize(0)));

        mvc.perform(json(as(ADMIN, patch("/api/v1/menu/{id}/availability", id)), """
                        {"available": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Can", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(id)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.parameters.name").value("Testi Kebabı"));
        mvc.perform(get("/api/v1/menu").param("includeUnavailable", "false"))
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));

        mvc.perform(as(ADMIN, delete("/api/v1/menu/{id}", id))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/menu/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MENU_ITEM_NOT_FOUND"));
        mvc.perform(get("/api/v1/menu")).andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));
    }

    @Test
    void validatesMenuItems() throws Exception {
        mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": " ", "category": "MAIN", "price": -5, "prepTimeMinutes": 999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("price")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("prepTimeMinutes")));
    }

    @Test
    void onlyManagersMayChangeTheMenu() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/menu")), "{}")).andExpect(status().isForbidden());
        mvc.perform(json(as(CHEF, patch("/api/v1/menu/1/availability")), """
                {"available": false}""")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/menu/1")).andExpect(status().isUnauthorized());
    }
}
