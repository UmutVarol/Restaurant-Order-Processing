package com.umutvarol.restaurant.menu;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductOptionsApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void theMenuListsOptionsAndAnOrderLineIsPricedWithThem() throws Exception {
        long kebab = menuItemId("Adana Kebap");
        String item = mvc.perform(get("/api/v1/menu/{id}", kebab))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionGroups", hasSize(2)))
                .andExpect(jsonPath("$.optionGroups[0].name").value("Acılık"))
                .andExpect(jsonPath("$.optionGroups[0].minSelect").value(1))
                .andReturn().getResponse().getContentAsString();
        List<Integer> extras = JsonPath.read(item, "$.optionGroups[1].choices[*].id");
        Integer hot = JsonPath.read(item, "$.optionGroups[0].choices[2].id");

        // 520 + 25 + 20 = 565 per kebab
        mvc.perform(json(as(WAITER, post("/api/v1/orders/quote")), """
                        {"type": "TAKEAWAY", "items": [{"menuItemId": %d, "quantity": 2, "optionIds": [%d, %d, %d]}]}"""
                        .formatted(kebab, hot, extras.get(0), extras.get(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(565.00))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(1130.00))
                .andExpect(jsonPath("$.lines[0].options[*].choice").value(org.hamcrest.Matchers.contains(
                        "Bol acılı", "Közlenmiş domates", "Ekstra lavaş")));

        // Without choices, the required group gets its free default.
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Options", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(kebab)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(520.00))
                .andExpect(jsonPath("$.lines[0].options[0].group").value("Acılık"))
                .andExpect(jsonPath("$.lines[0].options[0].choice").value("Normal"));
    }

    @Test
    void refusesOptionsOfAnotherDish() throws Exception {
        String tea = mvc.perform(get("/api/v1/menu/{id}", menuItemId("Demlik Çay")))
                .andReturn().getResponse().getContentAsString();
        Integer teaChoice = JsonPath.read(tea, "$.optionGroups[0].choices[0].id");
        mvc.perform(json(as(WAITER, post("/api/v1/orders/quote")), """
                        {"type": "TAKEAWAY", "items": [{"menuItemId": %d, "quantity": 1, "optionIds": [%d]}]}"""
                        .formatted(menuItemId("Adana Kebap"), teaChoice)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"));
    }

    @Test
    void managersEditOptionsInTheMenu() throws Exception {
        String body = """
                {"name": "Test Pide", "category": "MAIN", "price": 300, "prepTimeMinutes": 12,
                 "optionGroups": [{"name": "Size", "minSelect": 1, "maxSelect": 1,
                                   "choices": [{"name": "Regular"}, {"name": "Large", "priceDelta": 80}]}]}""";
        String created = mvc.perform(json(as(ADMIN, post("/api/v1/menu")), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.optionGroups[0].choices[1].priceDelta").value(80.00))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", id)), """
                        {"name": "Test Pide", "category": "MAIN", "price": 300,
                         "optionGroups": [{"name": "Size", "minSelect": 2, "maxSelect": 1, "choices": [{"name": "Regular"}]}]}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_OPTIONS_INVALID"));
        mvc.perform(json(as(WAITER, put("/api/v1/menu/{id}", id)), body)).andExpect(status().isForbidden());
    }
}
