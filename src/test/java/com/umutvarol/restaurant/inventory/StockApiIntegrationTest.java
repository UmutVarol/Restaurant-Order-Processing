package com.umutvarol.restaurant.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The stock ledger, recipes with options, deduction on completion and automatic sell-out. */
class StockApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void theLedgerRecordsDeliveriesWasteAndCountsInAnyFittingUnit() throws Exception {
        long flour = ingredient("Flour " + suffix(), "GRAM", "40", "500", true);

        movement(flour, """
                {"kind": "DELIVERY", "quantity": 2, "unit": "KG", "unitCost": 45, "supplier": "Değirmen"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(2000.0))
                .andExpect(jsonPath("$.unitCost").value(45.0));
        movement(flour, """
                {"kind": "WASTE", "quantity": 250, "unit": "G"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WASTE_REASON_REQUIRED"));
        movement(flour, """
                {"kind": "WASTE", "quantity": 250, "unit": "G", "reason": "Got damp"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(-250.0));
        movement(flour, """
                {"kind": "WASTE", "quantity": 1, "unit": "L", "reason": "Spilt"}""")
                .andExpect(jsonPath("$.code").value("UNIT_MISMATCH"));
        movement(flour, """
                {"kind": "SALE", "quantity": 1, "unit": "G"}""")
                .andExpect(jsonPath("$.code").value("MOVEMENT_KIND_NOT_ALLOWED"));
        movement(flour, """
                {"kind": "DELIVERY", "quantity": 0, "unit": "G"}""")
                .andExpect(jsonPath("$.code").value("QUANTITY_REQUIRED"));
        // the shelf holds 1.5 kg: the count records the 250 g nobody can explain
        movement(flour, """
                {"kind": "COUNT", "quantity": 1.5, "unit": "KG", "reason": "Weekly count"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(-250.0));

        mvc.perform(as(ADMIN, get("/api/v1/stock/ingredients")))
                .andExpect(jsonPath("$[?(@.id == %d)].onHand".formatted(flour)).value(1500.0))
                .andExpect(jsonPath("$[?(@.id == %d)].cost".formatted(flour)).value(45.0))
                .andExpect(jsonPath("$[?(@.id == %d)].value".formatted(flour)).value(67.5))
                .andExpect(jsonPath("$[?(@.id == %d)].low".formatted(flour)).value(false));
        mvc.perform(as(ADMIN, get("/api/v1/stock/ingredients/{id}/movements", flour)))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].kind").value("COUNT"))
                .andExpect(jsonPath("$[2].supplier").value("Değirmen"));

        // something already counted cannot change its unit
        mvc.perform(json(as(ADMIN, put("/api/v1/stock/ingredients/{id}", flour)), """
                        {"name": "Flour", "unit": "PIECE", "cost": 45}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INGREDIENT_UNIT_LOCKED"));
    }

    @Test
    void completedOrdersUseTheirRecipesAndOptionsAndSellDishesOut() throws Exception {
        String name = "Stock Test Dish " + suffix();
        long cheese = ingredient("Cheese " + name, "GRAM", "400", "150", true);
        long garlic = ingredient("Garlic " + name, "GRAM", "100", null, true);
        long chives = ingredient("Chives " + name, "GRAM", "200", null, true);
        long salt = ingredient("Salt " + name, "GRAM", "10", null, false);
        movement(cheese, """
                {"kind": "DELIVERY", "quantity": 300, "unit": "G"}""").andExpect(status().isCreated());
        movement(garlic, """
                {"kind": "DELIVERY", "quantity": 100, "unit": "G"}""").andExpect(status().isCreated());
        movement(chives, """
                {"kind": "DELIVERY", "quantity": 100, "unit": "G"}""").andExpect(status().isCreated());

        String dish = mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": "%s", "category": "MAIN", "price": 100,
                         "optionGroups": [{"name": "Size", "minSelect": 0, "maxSelect": 1, "choices": [{"name": "Large", "priceDelta": 20}]},
                                          {"name": "Herb", "minSelect": 0, "maxSelect": 1, "choices": [{"name": "Chives", "priceDelta": 0}]}]}"""
                        .formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long dishId = ((Number) JsonPath.read(dish, "$.id")).longValue();
        List<Number> choices = JsonPath.read(dish, "$.optionGroups[*].choices[0].id");

        mvc.perform(json(as(ADMIN, put("/api/v1/recipes/{id}", dishId)), """
                        {"lines": [{"ingredientId": %d, "quantity": 100}, {"ingredientId": %d, "quantity": 10},
                                   {"ingredientId": %d, "quantity": 2},
                                   {"ingredientId": %d, "quantity": 50, "optionGroup": "size", "optionChoice": "LARGE"},
                                   {"ingredientId": %d, "quantity": 5, "optionGroup": "Herb", "optionChoice": "Chives", "replacesIngredientId": %d}]}"""
                        .formatted(cheese, garlic, salt, cheese, chives, garlic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[3].optionGroup").value("Size"))
                .andExpect(jsonPath("$.lines[3].optionChoice").value("Large"))
                // 100 g at 400/kg + 10 g at 100/kg + 2 g at 10/kg = 40 + 1 + 0.02
                .andExpect(jsonPath("$.cost").value(41.02))
                .andExpect(jsonPath("$.foodCostPercent").value(41.0));
        mvc.perform(json(as(ADMIN, put("/api/v1/recipes/{id}", dishId)), """
                        {"lines": [{"ingredientId": %d, "quantity": 5, "optionGroup": "Size", "optionChoice": "Huge"}]}"""
                        .formatted(cheese)))
                .andExpect(jsonPath("$.code").value("RECIPE_OPTION_NOT_FOUND"));
        mvc.perform(as(ADMIN, delete("/api/v1/stock/ingredients/{id}", cheese)))
                .andExpect(jsonPath("$.code").value("INGREDIENT_IN_USE"));

        // one large portion with chives: 150 g cheese, chives instead of garlic
        long order = placeWithOptions(dishId, 1, choices);
        complete(order);
        mvc.perform(as(ADMIN, get("/api/v1/stock/ingredients")))
                .andExpect(jsonPath("$[?(@.id == %d)].onHand".formatted(cheese)).value(150.0))
                .andExpect(jsonPath("$[?(@.id == %d)].low".formatted(cheese)).value(true))
                .andExpect(jsonPath("$[?(@.id == %d)].onHand".formatted(garlic)).value(100.0))
                .andExpect(jsonPath("$[?(@.id == %d)].onHand".formatted(chives)).value(95.0));

        // a large portion more uses the rest of the cheese: the dish sells out
        complete(placeWithOptions(dishId, 1, List.of(choices.get(0))));
        mvc.perform(as(WAITER, get("/api/v1/menu/{id}", dishId)))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.stockOut").value(true));
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Too late", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(dishId)))
                .andExpect(jsonPath("$.code").value("MENU_ITEM_UNAVAILABLE"));

        // a delivery brings it back
        movement(cheese, """
                {"kind": "DELIVERY", "quantity": 1, "unit": "KG"}""").andExpect(status().isCreated());
        mvc.perform(as(WAITER, get("/api/v1/menu/{id}", dishId)))
                .andExpect(jsonPath("$.available").value(true));

        mvc.perform(as(ADMIN, get("/api/v1/stock/report")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.ingredientId == %d)].sold".formatted(cheese)).value(300.0))
                .andExpect(jsonPath("$.rows[?(@.ingredientId == %d)].soldCost".formatted(cheese)).value(120.0));
        mvc.perform(as(ADMIN, get("/api/v1/recipes/costs")))
                .andExpect(jsonPath("$[?(@.menuItemId == %d)].cost".formatted(dishId)).value(41.02));

        mvc.perform(as(ADMIN, delete("/api/v1/menu/{id}", dishId))).andExpect(status().isNoContent());
    }

    @Test
    void stockIsForManagersOnly() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/stock/ingredients"))).andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/recipes/costs"))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, get("/api/v1/stock/report").param("from", "2026-02-01").param("to", "2026-01-01")))
                .andExpect(jsonPath("$.code").value("REPORT_PERIOD_INVALID"));
        mvc.perform(json(as(ADMIN, post("/api/v1/stock/ingredients")), """
                        {"name": "", "unit": "GRAM", "cost": -1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private long ingredient(String name, String unit, String cost, String low, boolean tracked) throws Exception {
        String body = mvc.perform(json(as(ADMIN, post("/api/v1/stock/ingredients")), """
                        {"name": "%s", "unit": "%s", "cost": %s, "lowStockLevel": %s, "trackStock": %s}"""
                        .formatted(name, unit, cost, low, tracked)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions movement(long ingredient, String body) throws Exception {
        return mvc.perform(json(as(ADMIN, post("/api/v1/stock/ingredients/{id}/movements", ingredient)), body));
    }

    private long placeWithOptions(long dishId, int quantity, List<Number> optionIds) throws Exception {
        String body = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Stock", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": %d, "optionIds": %s}]}"""
                        .formatted(dishId, quantity, optionIds)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void complete(long orderId) throws Exception {
        payInFull(orderId, "CASH");
        for (String next : List.of("PREPARING", "READY", "COMPLETED")) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", orderId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(next));
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
