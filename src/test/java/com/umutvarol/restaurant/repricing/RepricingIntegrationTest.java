package com.umutvarol.restaurant.repricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Inflation tools: a bulk change is previewed, applied once (never twice, however often it is sent), taken back
 * without undoing a price someone changed since, and refused when it would make a price zero; the margin check flags a
 * dish whose ingredients take too much of its price and suggests the price that brings it back to the target.
 */
class RepricingIntegrationTest extends ApiIntegrationTest {

    @Test
    void aBulkChangeIsPreviewedAppliedOnceAndTakenBack() throws Exception {
        long tea = dish("Bulk tea " + suffix(), "40");
        long juice = dish("Bulk juice " + suffix(), "47.30");
        String rule = """
                {"mode": "PERCENT", "value": 10, "rounding": "ONE", "itemIds": [%d, %d]%s}""";

        // the preview changes nothing; 40 + 10 % = 44, 47.30 + 10 % = 52.03 rounded to 52
        String preview = mvc.perform(as(ADMIN, json(post("/api/v1/repricing/preview"), rule.formatted(tea, juice, ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.lines[?(@.itemId == %d)].newPrice".formatted(tea)).value(org.hamcrest.Matchers.contains(44.00)))
                .andExpect(jsonPath("$.lines[?(@.itemId == %d)].newPrice".formatted(juice)).value(org.hamcrest.Matchers.contains(52.00)))
                .andReturn().getResponse().getContentAsString();
        assertThat(price(tea)).isEqualTo(40.0);
        mvc.perform(as(WAITER, json(post("/api/v1/repricing/preview"), rule.formatted(tea, juice, "")))).andExpect(status().isForbidden());

        // applying needs what the preview showed
        mvc.perform(as(ADMIN, json(post("/api/v1/repricing/apply"), rule.formatted(tea, juice, ""))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BULK_PRICE_PREVIEW_NEEDED"));
        String expected = ", \"expected\": " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                JsonPath.<Map<String, Object>>read(preview, "$.expected"));
        String applied = mvc.perform(as(ADMIN, json(post("/api/v1/repricing/apply"), rule.formatted(tea, juice, expected))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.revertible").value(true))
                .andReturn().getResponse().getContentAsString();
        long change = ((Number) JsonPath.read(applied, "$.id")).longValue();
        assertThat(price(tea)).isEqualTo(44.0);
        assertThat(price(juice)).isEqualTo(52.0);

        // the same change sent again (a double tap) is refused: the prices it expects are gone
        mvc.perform(as(ADMIN, json(post("/api/v1/repricing/apply"), rule.formatted(tea, juice, expected))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICES_CHANGED"));
        assertThat(price(tea)).isEqualTo(44.0);

        mvc.perform(as(ADMIN, get("/api/v1/repricing/changes")))
                .andExpect(jsonPath("$[0].id").value(change))
                .andExpect(jsonPath("$[0].mode").value("PERCENT"));

        // someone changes the juice by hand; taking the change back leaves the juice alone
        setPrice(juice, "60");
        mvc.perform(as(ADMIN, post("/api/v1/repricing/changes/{id}/revert", change)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.change.revertible").value(false));
        assertThat(price(tea)).isEqualTo(40.0);
        assertThat(price(juice)).isEqualTo(60.0);
        mvc.perform(as(ADMIN, post("/api/v1/repricing/changes/{id}/revert", change)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGE_REVERTED"));
    }

    @Test
    void aChangeThatWouldBreakAPriceIsRefused() throws Exception {
        long cheap = dish("Bulk cheap " + suffix(), "20");
        mvc.perform(as(ADMIN, json(post("/api/v1/repricing/preview"), """
                        {"mode": "AMOUNT", "value": -25, "itemIds": [%d]}""".formatted(cheap))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BULK_PRICE_INVALID"))
                .andExpect(jsonPath("$.parameters.name").value(org.hamcrest.Matchers.startsWith("Bulk cheap")));
        for (String rule : List.of("""
                {"mode": "PERCENT", "value": 300, "itemIds": [%d]}""", """
                {"mode": "PERCENT", "value": 0, "itemIds": [%d]}""", """
                {"mode": "EXACT", "prices": [{"itemId": %d, "price": 0}]}""")) {
            mvc.perform(as(ADMIN, json(post("/api/v1/repricing/preview"), rule.formatted(cheap))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("BULK_PRICE_RULE"));
        }
        mvc.perform(as(ADMIN, json(post("/api/v1/repricing/preview"), """
                        {"mode": "PERCENT", "value": 5, "itemIds": [987654321]}""")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BULK_PRICE_NOTHING"));
        assertThat(price(cheap)).isEqualTo(20.0);
    }

    @Test
    void aDishWhoseIngredientsTakeTooMuchGetsASuggestedPrice() throws Exception {
        long dish = dish("Margin dish " + suffix(), "110");
        String created = mvc.perform(as(ADMIN, json(post("/api/v1/stock/ingredients"), """
                        {"name": "Margin meat %s", "unit": "GRAM", "cost": 500, "trackStock": false}""".formatted(suffix()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long meat = ((Number) JsonPath.read(created, "$.id")).longValue();
        mvc.perform(as(ADMIN, json(put("/api/v1/recipes/{id}", dish), """
                        {"lines": [{"ingredientId": %d, "quantity": 100}]}""".formatted(meat)))).andExpect(status().isOk());

        // 100 g at 500/kg is 50; the price before VAT is 100, so 50 %, against a 30 % target
        mvc.perform(as(ADMIN, json(put("/api/v1/repricing/settings"), """
                        {"targetFoodCost": 30, "rounding": "ONE"}"""))).andExpect(status().isOk());
        String margins = mvc.perform(as(ADMIN, get("/api/v1/repricing/margins")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(30.00))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> row = JsonPath.<List<Map<String, Object>>>read(margins, "$.items[?(@.itemId == %d)]".formatted(dish)).getFirst();
        assertThat(((Number) row.get("foodCost")).doubleValue()).isEqualTo(50.0);
        assertThat(row.get("status")).isEqualTo("HIGH");
        // 50 / 30 % = 166.67 before VAT, 183.33 with it, up to the next lira
        assertThat(((Number) row.get("suggestedPrice")).doubleValue()).isEqualTo(184.0);
        assertThat(((Number) JsonPath.read(margins, "$.needing")).intValue()).isPositive();

        // one tap: the suggested price
        String preview = mvc.perform(as(ADMIN, json(post("/api/v1/repricing/preview"), """
                        {"mode": "EXACT", "prices": [{"itemId": %d, "price": 184}]}""".formatted(dish))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].foodCostAfter").value(29.9))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(ADMIN, json(post("/api/v1/repricing/apply"), """
                        {"mode": "EXACT", "prices": [{"itemId": %d, "price": 184}], "expected": {"%d": %s}}"""
                        .formatted(dish, dish, JsonPath.read(preview, "$.lines[0].oldPrice").toString()))))
                .andExpect(status().isOk());
        assertThat(price(dish)).isEqualTo(184.0);

        mvc.perform(as(ADMIN, json(put("/api/v1/repricing/settings"), """
                        {"targetFoodCost": 2, "rounding": "ONE"}"""))).andExpect(status().isBadRequest());
    }

    private long dish(String name, String price) throws Exception {
        String body = mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                        {"name": "%s", "category": "DRINK", "price": %s}""".formatted(name, price))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private double price(long id) throws Exception {
        return ((Number) JsonPath.read(mvc.perform(get("/api/v1/menu/{id}", id)).andReturn().getResponse().getContentAsString(), "$.price"))
                .doubleValue();
    }

    private void setPrice(long id, String price) throws Exception {
        String dish = mvc.perform(get("/api/v1/menu/{id}", id)).andReturn().getResponse().getContentAsString();
        mvc.perform(as(ADMIN, json(put("/api/v1/menu/{id}", id), """
                        {"name": "%s", "category": "DRINK", "price": %s}""".formatted(JsonPath.read(dish, "$.name"), price))))
                .andExpect(status().isOk());
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
