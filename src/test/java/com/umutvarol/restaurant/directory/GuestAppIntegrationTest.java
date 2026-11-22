package com.umutvarol.restaurant.directory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Round three, the guest app: a branch placed in the directory is found by a guest standing in it (and not by one
 * across town), its public page shows its menu and what verified guests said, a check-in counts on the panel, and
 * only a paid visit can be rated, once; the restaurant answers in public.
 */
class GuestAppIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = 40.98712;
    private static final double LNG = 29.02871;

    @Test
    void aGuestFindsTheRestaurantChecksInAndRatesAPaidVisitWhichTheRestaurantAnswers() throws Exception {
        String branches = mvc.perform(as(ADMIN, get("/api/v1/branches"))).andReturn().getResponse().getContentAsString();
        long branchId = ((Number) JsonPath.read(branches, "$[0].id")).longValue();
        String code = JsonPath.read(branches, "$[0].code");
        try {
            // not listed yet: nobody finds it, its page does not exist
            Assertions.assertThat((List<?>) JsonPath.read(nearby(LAT, LNG, 20), "$.venues")).isEmpty();

            // listing needs a position; waiters cannot place the branch
            mvc.perform(as(ADMIN, json(put("/api/v1/directory/{id}", branchId), """
                            {"latitude": null, "longitude": null, "address": null, "listed": true}""")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DIRECTORY_POSITION_NEEDED"));
            mvc.perform(as(WAITER, json(put("/api/v1/directory/{id}", branchId), """
                            {"latitude": 1, "longitude": 1, "listed": false}"""))).andExpect(status().isForbidden());
            mvc.perform(as(ADMIN, json(put("/api/v1/directory/{id}", branchId), """
                            {"latitude": %s, "longitude": %s, "address": "Moda Cd. 12", "listed": true}""".formatted(LAT, LNG))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.listed").value(true));

            // a guest 15 m away with a good fix is greeted with it; one 3 km away does not see it
            String near = nearby(LAT + 0.0001, LNG + 0.00005, 10);
            Assertions.assertThat((Boolean) JsonPath.read(near, "$.confident")).isTrue();
            Assertions.assertThat(((Number) JsonPath.read(near, "$.venues[0].distance")).intValue()).isBetween(5, 30);
            Assertions.assertThat((String) JsonPath.read(near, "$.venues[0].branch")).isEqualTo(code);
            String business = JsonPath.read(near, "$.venues[0].business");
            Assertions.assertThat((List<?>) JsonPath.read(nearby(LAT + 0.03, LNG, 20), "$.venues")).isEmpty();

            // the public page: who it is and its menu; no sign-in
            mvc.perform(get("/api/v1/public/venue/{b}/{c}", business, code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.address").value("Moda Cd. 12"))
                    .andExpect(jsonPath("$.menu[*].name", Matchers.hasItem("Ayran")));
            mvc.perform(get("/api/v1/public/venue/{b}/{c}", business, "no-such-branch")).andExpect(status().isNotFound());

            // "I am here" counts on the panel; the device is kept only as a hash
            mvc.perform(json(post("/api/v1/public/venue/{b}/{c}/checkin", business, code), """
                            {"device": "test-device-0123456789", "method": "NEARBY"}""")).andExpect(status().isNoContent());
            mvc.perform(as(ADMIN, get("/api/v1/ratings/summary")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.insideNow", Matchers.greaterThanOrEqualTo(1)));
            mvc.perform(as(WAITER, get("/api/v1/ratings/summary"))).andExpect(status().isForbidden());

            // a visit: before it is paid it cannot be rated
            long orderId = placeTakeaway("Puan Misafiri", "Ayran", 2);
            long billId = billIdOf(orderId);
            String link = mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/links", billId)), "{}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String token = JsonPath.read(link, "$.token");
            mvc.perform(get("/api/v1/public/pay/{t}/rating", token))
                    .andExpect(jsonPath("$.allowed").value(false))
                    .andExpect(jsonPath("$.dishes[0]").value("Ayran"));
            mvc.perform(json(post("/api/v1/public/pay/{t}/rating", token), """
                            {"stars": 5}"""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("RATING_NOT_PAID"));

            payInFull(orderId, "CASH");
            mvc.perform(json(post("/api/v1/public/pay/{t}/rating", token), """
                            {"stars": 9}""")).andExpect(jsonPath("$.code").value("RATING_STARS_INVALID"));
            mvc.perform(json(post("/api/v1/public/pay/{t}/rating", token), """
                            {"stars": 4, "tags": ["TASTE", "NOT_A_TAG"], "liked": ["Ayran", "Not on the bill"],
                             "comment": "Ayran soğuktu, güzeldi."}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rated").value(true));
            mvc.perform(json(post("/api/v1/public/pay/{t}/rating", token), """
                            {"stars": 1}"""))
                    .andExpect(jsonPath("$.code").value("RATING_ALREADY_GIVEN"));

            // the restaurant reads it (unknown tags and dishes dropped) and answers in public
            String recent = mvc.perform(as(ADMIN, get("/api/v1/ratings").param("limit", "5")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long ratingId = ((Number) JsonPath.read(recent, "$[0].id")).longValue();
            Assertions.assertThat((List<Object>) JsonPath.read(recent, "$[0].tags")).containsExactly("TASTE");
            Assertions.assertThat((List<Object>) JsonPath.read(recent, "$[0].liked")).containsExactly("Ayran");
            mvc.perform(as(ADMIN, json(put("/api/v1/ratings/{id}/reply", ratingId), """
                            {"reply": "Teşekkürler, yine bekleriz!"}""")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reply").value("Teşekkürler, yine bekleriz!"));
            mvc.perform(get("/api/v1/public/venue/{b}/{c}", business, code))
                    .andExpect(jsonPath("$.ratings", Matchers.greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.reviews[*].reply", Matchers.hasItem("Teşekkürler, yine bekleriz!")));
            mvc.perform(get("/api/v1/ratings")).andExpect(status().isUnauthorized());
        } finally {
            mvc.perform(as(ADMIN, json(put("/api/v1/directory/{id}", branchId), """
                    {"latitude": null, "longitude": null, "address": null, "listed": false}"""))).andExpect(status().isOk());
        }
    }

    private String nearby(double lat, double lng, int accuracy) throws Exception {
        return mvc.perform(get("/api/v1/public/venues/nearby").param("lat", String.valueOf(lat))
                        .param("lng", String.valueOf(lng)).param("accuracy", String.valueOf(accuracy)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
