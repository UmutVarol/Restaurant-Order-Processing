package com.umutvarol.restaurant.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Planning the day: what sold before becomes the day's likely sales, the prep list shows what those portions need and
 * what is short, a dish is ticked as prepared (once, whoever ticks it), and waste takes its ingredients out of stock,
 * once however often a tablet sends it, and adds up by reason against sales.
 */
class PlanningIntegrationTest extends ApiIntegrationTest {

    /** The restaurant's own calendar, which is the one the server plans in. */
    private static final java.time.ZoneId ISTANBUL = java.time.ZoneId.of("Europe/Istanbul");

    @Test
    void salesBecomeAPrepListWithWhatIsShortAndWasteAddsUp() throws Exception {
        String name = "Plan dish " + suffix();
        long dish = id(mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                        {"name": "%s", "category": "MAIN", "price": 200}""".formatted(name)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        long rice = id(mvc.perform(as(ADMIN, json(post("/api/v1/stock/ingredients"), """
                        {"name": "Plan rice %s", "unit": "GRAM", "cost": 100, "trackStock": true}""".formatted(suffix()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(as(ADMIN, json(post("/api/v1/stock/ingredients/{id}/movements", rice), """
                        {"kind": "DELIVERY", "quantity": 400, "unit": "G"}"""))).andExpect(status().isCreated());
        mvc.perform(as(ADMIN, json(put("/api/v1/recipes/{id}", dish), """
                        {"lines": [{"ingredientId": %d, "quantity": 50}]}""".formatted(rice)))).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            placeTakeaway("Plan " + i, name, 3);
        }

        // a week from today: today is the only day like it, so the estimate rests on recent days, roughly 9
        LocalDate day = LocalDate.now(ISTANBUL).plusDays(7);
        String prep = mvc.perform(as(CHEF, get("/api/v1/planning/prep").param("date", day.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> line = only(prep, "$.dishes[?(@.menuItemId == %d)]".formatted(dish));
        assertThat(((Number) line.get("expected")).intValue()).isEqualTo(9);
        assertThat(line.get("confidence")).isEqualTo("LOW");
        // 9 portions × 50 g = 450 g against 400 g on hand: 50 g short, listed first
        Map<String, Object> need = only(prep, "$.ingredients[?(@.ingredientId == %d)]".formatted(rice));
        assertThat(((Number) need.get("needed")).doubleValue()).isEqualTo(450.0);
        assertThat(((Number) need.get("shortBy")).doubleValue()).isEqualTo(50.0);
        assertThat(((Number) JsonPath.read(prep, "$.shortages")).intValue()).isPositive();

        // ticked once, by whoever does it first; unticked again
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(CHEF, json(put("/api/v1/planning/prep/{date}/{item}", day, dish), """
                            {"prepared": true}"""))).andExpect(status().isNoContent());
        }
        Map<String, Object> ticked = only(mvc.perform(as(ADMIN, get("/api/v1/planning/prep").param("date", day.toString())))
                .andReturn().getResponse().getContentAsString(), "$.dishes[?(@.menuItemId == %d)]".formatted(dish));
        assertThat(ticked.get("prepared")).isEqualTo(true);
        assertThat(ticked.get("preparedBy")).isEqualTo(CHEF);
        mvc.perform(as(CHEF, json(put("/api/v1/planning/prep/{date}/{item}", day, dish), """
                        {"prepared": false}"""))).andExpect(status().isNoContent());
        mvc.perform(as(CHEF, get("/api/v1/planning/prep").param("date", LocalDate.now(ISTANBUL).plusDays(30).toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PREP_DAY_INVALID"));
        mvc.perform(as(WAITER, get("/api/v1/planning/prep"))).andExpect(status().isForbidden());

        // two portions thrown away take 100 g out; sent twice with the same key, once
        String key = "tablet-" + suffix();
        String body = """
                {"menuItemId": %d, "quantity": 2, "reason": "OVERPRODUCED", "requestKey": "%s"}""".formatted(dish, key);
        String first = mvc.perform(as(CHEF, json(post("/api/v1/planning/waste"), body))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cost").value(10.00))
                .andReturn().getResponse().getContentAsString();
        String again = mvc.perform(as(CHEF, json(post("/api/v1/planning/waste"), body))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(again, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(onHand(rice)).isEqualTo(400.0 - 100.0);
        // a waiter records what a guest sent back; an ingredient by weight
        mvc.perform(as(WAITER, json(post("/api/v1/planning/waste"), """
                        {"ingredientId": %d, "quantity": 20, "unit": "G", "reason": "SPOILED", "note": "smelled off"}""".formatted(rice))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cost").value(2.00));
        assertThat(onHand(rice)).isEqualTo(280.0);

        for (String bad : List.of("""
                {"menuItemId": %d, "ingredientId": %d, "quantity": 1, "reason": "OTHER"}""".formatted(dish, rice), """
                {"menuItemId": %d, "quantity": 1.5, "reason": "OTHER"}""".formatted(dish), """
                {"ingredientId": %d, "quantity": 1, "reason": "OTHER"}""".formatted(rice))) {
            mvc.perform(as(CHEF, json(post("/api/v1/planning/waste"), bad)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("WASTE_INVALID"));
        }
        mvc.perform(as(CHEF, json(post("/api/v1/planning/waste"), """
                        {"menuItemId": %d, "quantity": 1}""".formatted(dish))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WASTE_REASON_REQUIRED"));

        // the manager's report: by reason, by item, against sales
        String today = LocalDate.now(ISTANBUL).toString();
        String report = mvc.perform(as(ADMIN, get("/api/v1/planning/waste").param("from", today).param("to", today)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(report, "$.cost")).doubleValue()).isGreaterThanOrEqualTo(12.0);
        assertThat((List<?>) JsonPath.read(report, "$.byReason[?(@.reason == 'OVERPRODUCED')]")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(report, "$.topItems[?(@.name == '%s')]".formatted(name))).isNotEmpty();
        mvc.perform(as(CHEF, get("/api/v1/planning/waste").param("from", today).param("to", today))).andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/planning/waste/today"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '%s')].reason".formatted(name)).value(org.hamcrest.Matchers.hasItem("OVERPRODUCED")));
        mvc.perform(as(ADMIN, get("/api/v1/planning/waste").param("from", today).param("to", LocalDate.now(ISTANBUL).plusDays(200).toString())))
                .andExpect(status().isUnprocessableContent());
    }

    private double onHand(long ingredient) throws Exception {
        String list = mvc.perform(as(ADMIN, get("/api/v1/stock/ingredients"))).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<List<Object>>read(list, "$[?(@.id == %d)].onHand".formatted(ingredient)).getFirst()).doubleValue();
    }

    private static Map<String, Object> only(String json, String path) {
        List<Map<String, Object>> found = JsonPath.read(json, path);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
