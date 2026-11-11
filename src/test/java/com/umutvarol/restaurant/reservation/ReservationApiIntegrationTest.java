package com.umutvarol.restaurant.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReservationApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aTableIsNeverBookedTwiceForOverlappingTimes() throws Exception {
        long table = tableId(newTable());
        Instant eight = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);

        long first = book("Ayşe Yılmaz", 4, eight, table, 90);
        mvc.perform(as(WAITER, get("/api/v1/reservations").param("date", eight.atZone(java.time.ZoneId.of("Europe/Istanbul")).toLocalDate().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].status".formatted(first)).value(hasItem("BOOKED")));

        // half an hour later overlaps; straight after it does not
        mvc.perform(json(as(WAITER, post("/api/v1/reservations")), body("Mehmet", 2, eight.plus(Duration.ofMinutes(30)), table, 90)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_ALREADY_BOOKED"));
        book("Mehmet", 2, eight.plus(Duration.ofMinutes(90)), table, 90);

        // the free-table search agrees
        mvc.perform(as(WAITER, get("/api/v1/reservations/free-tables")
                        .param("startsAt", eight.plus(Duration.ofMinutes(30)).toString()).param("partySize", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem((int) table))));

        // too many for the table
        mvc.perform(json(as(WAITER, post("/api/v1/reservations")), body("Big party", 8, eight.plus(Duration.ofHours(5)), table, 90)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_TOO_SMALL"));

        // cancelling frees the time again
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/cancel", first)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        book("Zeynep", 2, eight.plus(Duration.ofMinutes(30)), table, 60);
    }

    @Test
    void aWalkInIsSeatedFinishesWhenTheBillIsPaidAndTheFloorPlanShowsIt() throws Exception {
        String code = newTable();
        long table = tableId(code);
        String walkIn = mvc.perform(json(as(WAITER, post("/api/v1/reservations/walk-ins")), """
                        {"guestName": "Kapıdaki Aile", "partySize": 3, "quotedMinutes": 15, "phone": "+90 532 000 00 00"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.kind").value("WALK_IN"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(walkIn, "$.id")).longValue();

        mvc.perform(json(as(WAITER, post("/api/v1/reservations/{id}/seat", id)), """
                        {"tableId": %d}""".formatted(table)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEATED"))
                .andExpect(jsonPath("$.tableCode").value(code));

        String row = "$[?(@.code == '%s')]".formatted(code);
        mvc.perform(as(CHEF, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(row + ".seatedParty.guestName").value(hasItem("Kapıdaki Aile")))
                .andExpect(jsonPath(row + ".seatedParty.partySize").value(hasItem(3)));

        // nobody else can be seated there now
        long other = walkIn("Second party", 2);
        mvc.perform(json(as(WAITER, post("/api/v1/reservations/{id}/seat", other)), """
                        {"tableId": %d}""".formatted(table)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));

        // they order, eat and pay: the visit is over
        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Kapıdaki Aile", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(table, menuItemId("Ayran"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long order = ((Number) JsonPath.read(created, "$.id")).longValue();
        for (int step = 0; step < 3; step++) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", order))).andExpect(status().isOk());
        }
        payInFull(order, "CASH");

        assertThat(statusOf(id)).isEqualTo("FINISHED");
        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath(row + ".seatedParty").value(hasItem((Object) null)));
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/cancel", other))).andExpect(status().isOk());
    }

    @Test
    void seatingAtATableBookedSoonNeedsTheHostToOverrideIt() throws Exception {
        String code = newTable();
        long table = tableId(code);
        long booking = book("Akşam Rezervasyonu", 2, Instant.now().plus(Duration.ofMinutes(40)), table, 90);

        mvc.perform(as(WAITER, get("/api/v1/floor/tables")))
                .andExpect(jsonPath("$[?(@.code == '%s')].nextBooking.reservationId".formatted(code)).value(hasItem((int) booking)));

        long walkIn = walkIn("Hızlı yemek", 2);
        mvc.perform(json(as(WAITER, post("/api/v1/reservations/{id}/seat", walkIn)), """
                        {"tableId": %d}""".formatted(table)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_BOOKED_SOON"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Akşam Rezervasyonu")));
        mvc.perform(json(as(WAITER, post("/api/v1/reservations/{id}/seat", walkIn)), """
                        {"tableId": %d, "overrideBooking": true}""".formatted(table)))
                .andExpect(status().isOk());

        // the walk-in left without ordering
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/finish", walkIn)))
                .andExpect(jsonPath("$.status").value("FINISHED"));
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/seat", booking).contentType("application/json")
                        .content("{\"tableId\": %d}".formatted(table))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEATED"));
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/finish", booking))).andExpect(status().isOk());
        mvc.perform(as(WAITER, post("/api/v1/reservations/{id}/finish", booking)))
                .andExpect(status().isConflict());
    }

    @Test
    void lateBookingsBecomeNoShowsAndOldPhoneNumbersAreErased() throws Exception {
        long late = book("Gelmeyen", 2, Instant.now().minus(Duration.ofMinutes(10)), null, 90);
        jdbc.update("UPDATE reservations SET starts_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(25))), late);
        service.markNoShows();
        assertThat(statusOf(late)).isEqualTo("NO_SHOW");

        jdbc.update("UPDATE reservations SET starts_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(31))), late);
        assertThat(service.eraseOldContacts()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone FROM reservations WHERE id = ?", String.class, late)).isNull();
    }

    @Test
    void theKitchenCannotSeeGuestsContactDetailsAndInputIsChecked() throws Exception {
        mvc.perform(as(CHEF, get("/api/v1/reservations"))).andExpect(status().isForbidden());
        mvc.perform(json(as(WAITER, post("/api/v1/reservations")), """
                        {"guestName": "X", "partySize": 2, "startsAt": "%s", "phone": "call me maybe"}"""
                        .formatted(Instant.now().plus(Duration.ofDays(1)))))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(WAITER, post("/api/v1/reservations")), body("Dün", 2, Instant.now().minus(Duration.ofDays(1)), null, 90)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESERVATION_IN_PAST"));

        long id = book("Değişen", 2, Instant.now().plus(Duration.ofDays(3)), null, 90);
        mvc.perform(json(as(WAITER, put("/api/v1/reservations/{id}", id)), body("Değişen", 5, Instant.now().plus(Duration.ofDays(3)), null, 120)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(5))
                .andExpect(jsonPath("$.durationMinutes").value(120));
    }

    private long book(String name, int party, Instant at, Long table, int minutes) throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/reservations")), body(name, party, at, table, minutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    private long walkIn(String name, int party) throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/reservations/walk-ins")), """
                        {"guestName": "%s", "partySize": %d}""".formatted(name, party)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, id);
    }

    private static String body(String name, int party, Instant at, Long table, int minutes) {
        return """
                {"guestName": "%s", "phone": "0532 111 22 33", "partySize": %d, "startsAt": "%s", "durationMinutes": %d%s}"""
                .formatted(name, party, at, minutes, table == null ? "" : ", \"tableId\": " + table);
    }
}
