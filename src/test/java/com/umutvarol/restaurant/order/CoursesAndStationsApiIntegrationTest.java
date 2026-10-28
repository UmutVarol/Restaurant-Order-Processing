package com.umutvarol.restaurant.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Dishes go to their station; later courses wait until the waiter fires them; cooks tick dishes off. */
class CoursesAndStationsApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void laterCoursesWaitUntilTheyAreFired() throws Exception {
        String order = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Course Test", "paymentMethod": "CASH", "holdCourses": true,
                         "items": [{"menuItemId": %d, "quantity": 1}, {"menuItemId": %d, "quantity": 2},
                                   {"menuItemId": %d, "quantity": 1}, {"menuItemId": %d, "quantity": 1, "course": 3}]}"""
                        .formatted(menuItemId("Mercimek Çorbası"), menuItemId("Adana Kebap"), menuItemId("Künefe"),
                                menuItemId("Türk Kahvesi"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].station").value("COLD"))
                .andExpect(jsonPath("$.lines[0].course").value(1))
                .andExpect(jsonPath("$.lines[0].firedAt").isNotEmpty())
                .andExpect(jsonPath("$.lines[1].station").value("HOT"))
                .andExpect(jsonPath("$.lines[1].course").value(2))
                .andExpect(jsonPath("$.lines[1].firedAt").doesNotExist())
                .andExpect(jsonPath("$.lines[3].station").value("BAR"))
                .andExpect(jsonPath("$.lines[3].course").value(3))
                .andExpect(jsonPath("$.heldCourses[0]").value(2))
                .andExpect(jsonPath("$.heldCourses[1]").value(3))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(order, "$.id")).longValue();

        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/courses/{course}/fire", id, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].firedAt").isNotEmpty())
                .andExpect(jsonPath("$.heldCourses.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].kind").value("FIRE"));
        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/courses/{course}/fire", id, 2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_FIRE"));
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/courses/{course}/fire", id, 3)))
                .andExpect(status().isForbidden());
        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/courses/{course}/fire", id, 7)))
                .andExpect(status().isBadRequest());

        // a cook ticks the soup off; a dish that is not fired yet cannot be
        long soup = ((Number) JsonPath.read(order, "$.lines[0].id")).longValue();
        long dessert = ((Number) JsonPath.read(order, "$.lines[2].id")).longValue();
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/lines/{line}/ready", id, soup)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].readyAt").isNotEmpty());
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/lines/{line}/ready", id, soup).param("ready", "false")))
                .andExpect(jsonPath("$.lines[0].readyAt").doesNotExist());
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/lines/{line}/ready", id, dessert)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LINE_NOT_FIRED"));
    }

    @Test
    void withoutHoldingEverythingGoesAtOnce() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "All at once", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}, {"menuItemId": %d, "quantity": 1}]}"""
                        .formatted(menuItemId("Humus"), menuItemId("Künefe"))))
                .andExpect(jsonPath("$.lines[1].firedAt").isNotEmpty())
                .andExpect(jsonPath("$.heldCourses").isEmpty());
    }

    @Test
    void managersRunStationsAndMoveDishesBetweenThem() throws Exception {
        mvc.perform(as(CHEF, get("/api/v1/stations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", org.hamcrest.Matchers.hasItems("COLD", "HOT", "DESSERT", "BAR")));
        String grill = mvc.perform(json(as(ADMIN, post("/api/v1/stations")), """
                        {"code": "GRILL", "name": "Ocakbaşı", "targetMinutes": 20}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long grillId = ((Number) JsonPath.read(grill, "$.id")).longValue();
        mvc.perform(json(as(ADMIN, post("/api/v1/stations")), """
                        {"code": "GRILL", "name": "Other", "targetMinutes": 20}"""))
                .andExpect(jsonPath("$.code").value("STATION_CODE_TAKEN"));
        mvc.perform(json(as(WAITER, post("/api/v1/stations")), """
                        {"code": "X1", "name": "X", "targetMinutes": 5}"""))
                .andExpect(status().isForbidden());

        long kofte = menuItemId("Köfte Tabağı");
        String item = mvc.perform(as(ADMIN, get("/api/v1/menu/{id}", kofte))).andReturn().getResponse().getContentAsString();
        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", kofte)), """
                        {"name": "Köfte Tabağı", "category": "MAIN", "price": %s, "stationCode": "GRILL"}"""
                        .formatted(JsonPath.read(item, "$.price").toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationCode").value("GRILL"));
        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", kofte)), """
                        {"name": "Köfte Tabağı", "category": "MAIN", "price": 470, "stationCode": "NOPE"}"""))
                .andExpect(jsonPath("$.code").value("STATION_NOT_FOUND"));
        mvc.perform(as(ADMIN, delete("/api/v1/stations/{id}", grillId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATION_IN_USE"));

        // back where it was, so other tests see the seeded menu
        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", kofte)), """
                        {"name": "Köfte Tabağı", "category": "MAIN", "price": 470, "stationCode": "HOT"}"""))
                .andExpect(status().isOk());
        mvc.perform(as(ADMIN, delete("/api/v1/stations/{id}", grillId))).andExpect(status().isNoContent());
        List<String> codes = JsonPath.read(mvc.perform(as(ADMIN, get("/api/v1/stations"))).andReturn().getResponse().getContentAsString(), "$[*].code");
        org.assertj.core.api.Assertions.assertThat(codes).doesNotContain("GRILL");
    }

    @Test
    void aNewDishWithoutAStationIsMadeAtItsCategorysStation() throws Exception {
        String dish = mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": "Station Default Test", "category": "DESSERT", "price": 99}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationCode").value("DESSERT"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(json(as(ADMIN, post("/api/v1/menu")), """
                        {"name": "Station Unknown Test", "category": "MAIN", "price": 99, "stationCode": "NOPE"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STATION_NOT_FOUND"));
        mvc.perform(as(ADMIN, delete("/api/v1/menu/{id}", ((Number) JsonPath.read(dish, "$.id")).longValue())))
                .andExpect(status().isNoContent());
    }
}
