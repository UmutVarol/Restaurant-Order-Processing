package com.umutvarol.restaurant.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.security.Role;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StaffTimeApiIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void staffClockThemselvesInOnceAndManagersFixForgottenClockOuts() throws Exception {
        String cook = person("asci", Role.KITCHEN);
        String token = login(cook);

        mvc.perform(with(token, post("/api/v1/time-clock/in")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockedIn").value(true));
        mvc.perform(with(token, post("/api/v1/time-clock/in")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CLOCKED_IN"));
        // the kitchen sees its own clock, not everyone's
        mvc.perform(with(token, get("/api/v1/time-clock/entries").param("from", today().toString()).param("to", today().toString())))
                .andExpect(status().isForbidden());

        String entries = mvc.perform(as(ADMIN, get("/api/v1/time-clock/entries")
                        .param("from", today().toString()).param("to", today().toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> open = JsonPath.read(entries, "$[?(@.username == '%s')].id".formatted(cook));
        assertThat(open).hasSize(1);

        mvc.perform(as(ADMIN, post("/api/v1/time-clock/users/{id}/out", idOf(cook))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockedOutBy").value(ADMIN));
        mvc.perform(with(token, post("/api/v1/time-clock/out")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CLOCKED_IN"));

        // a correction needs a reason and sane times, and keeps the reason
        String start = ZonedDateTime.now(ISTANBUL).minusHours(5).toInstant().toString();
        String end = ZonedDateTime.now(ISTANBUL).minusHours(1).toInstant().toString();
        mvc.perform(as(ADMIN, json(put("/api/v1/time-clock/entries/{id}", open.getFirst()), """
                        {"clockedInAt": "%s", "clockedOutAt": "%s", "reason": ""}""".formatted(start, end))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(ADMIN, json(put("/api/v1/time-clock/entries/{id}", open.getFirst()), """
                        {"clockedInAt": "%s", "clockedOutAt": "%s", "reason": "x"}""".formatted(end, start))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TIME_ENTRY_INVALID"));
        mvc.perform(as(ADMIN, json(put("/api/v1/time-clock/entries/{id}", open.getFirst()), """
                        {"clockedInAt": "%s", "clockedOutAt": "%s", "reason": "Forgot to clock in at 5"}""".formatted(start, end))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes").value(240))
                .andExpect(jsonPath("$.correctionReason").value("Forgot to clock in at 5"));
    }

    @Test
    void theRotaHasNoDoubleBookingsAndOnlyManagersPlanIt() throws Exception {
        String waiter = person("garson", Role.WAITER);
        long id = idOf(waiter);
        ZonedDateTime five = LocalDate.now(ISTANBUL).plusDays(3).atTime(17, 0).atZone(ISTANBUL);
        String shift = mvc.perform(as(ADMIN, json(post("/api/v1/shifts"), """
                        {"userId": %d, "startsAt": "%s", "endsAt": "%s", "note": "Teras"}"""
                        .formatted(id, five.toInstant(), five.plusHours(6).toInstant()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("Teras"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(ADMIN, json(post("/api/v1/shifts"), """
                        {"userId": %d, "startsAt": "%s", "endsAt": "%s"}"""
                        .formatted(id, five.plusHours(3).toInstant(), five.plusHours(8).toInstant()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_OVERLAP"));
        mvc.perform(as(ADMIN, json(post("/api/v1/shifts"), """
                        {"userId": %d, "startsAt": "%s", "endsAt": "%s"}"""
                        .formatted(id, five.toInstant(), five.plusHours(20).toInstant()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SHIFT_INVALID"));

        String day = five.toLocalDate().toString();
        mvc.perform(with(login(waiter), get("/api/v1/shifts").param("from", day).param("to", day)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '%s')].note".formatted(waiter)).value(org.hamcrest.Matchers.hasItem("Teras")));
        mvc.perform(as(WAITER, json(post("/api/v1/shifts"), """
                        {"userId": %d, "startsAt": "%s", "endsAt": "%s"}"""
                        .formatted(id, five.plusDays(1).toInstant(), five.plusDays(1).plusHours(2).toInstant()))))
                .andExpect(status().isForbidden());

        long shiftId = ((Number) JsonPath.read(shift, "$.id")).longValue();
        mvc.perform(as(ADMIN, delete("/api/v1/shifts/{id}", shiftId))).andExpect(status().isNoContent());
    }

    @Test
    void aDaysTipsAreSharedByMinutesAndRoleToTheKurus() throws Exception {
        LocalDate yesterday = today().minusDays(1);
        String a = person("garsonA", Role.WAITER);
        String b = person("garsonB", Role.WAITER);
        String cook = person("mutfak", Role.KITCHEN);
        String boss = person("mudur", Role.ADMIN);
        worked(a, yesterday, 10, 12);   // 120 min × 1
        worked(b, yesterday, 12, 13);   //  60 min × 1
        worked(cook, yesterday, 12, 13); //  60 min × 0.5
        worked(boss, yesterday, 12, 13); //  managers do not share by default

        // 10,00 TL of tips taken yesterday
        long order = placeTakeaway("Bahşiş", "Ayran", 1);
        String bill = mvc.perform(as(WAITER, get("/api/v1/bills/by-order/{id}", order)))
                .andReturn().getResponse().getContentAsString();
        String paid = mvc.perform(as(WAITER, json(post("/api/v1/bills/{id}/payments", ((Number) JsonPath.read(bill, "$.id")).longValue()), """
                        {"method": "CASH", "amount": %s, "tip": 10.00}""".formatted(JsonPath.read(bill, "$.balance").toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Timestamp noon = Timestamp.from(yesterday.atTime(12, 30).atZone(ISTANBUL).toInstant());
        jdbc.update("UPDATE payments SET completed_at = ?, created_at = ? WHERE id = ?", noon, noon,
                ((Number) JsonPath.read(paid, "$.payment.id")).longValue());

        String preview = mvc.perform(as(ADMIN, get("/api/v1/tips/pool").param("date", yesterday.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.tipsTotal").value(10.00))
                .andReturn().getResponse().getContentAsString();
        Map<String, BigDecimal> shares = shares(preview);
        // 120 : 60 : 30 of 10,00 = 5,714… / 2,857… / 1,428…; the two leftover kuruş go to the largest remainders
        assertThat(shares).containsEntry(a, new BigDecimal("5.71")).containsEntry(b, new BigDecimal("2.86"))
                .containsEntry(cook, new BigDecimal("1.43")).doesNotContainKey(boss);
        assertThat(shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10.00");

        mvc.perform(as(WAITER, json(post("/api/v1/tips/pool"), """
                        {"date": "%s"}""".formatted(yesterday)))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, json(post("/api/v1/tips/pool"), """
                        {"date": "%s"}""".formatted(yesterday))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.closedBy").value(ADMIN));
        mvc.perform(as(ADMIN, json(post("/api/v1/tips/pool"), """
                        {"date": "%s"}""".formatted(yesterday))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIP_POOL_CLOSED"));

        // each person sees what they got
        mvc.perform(with(login(a), get("/api/v1/time-clock/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tips[0].amount").value(5.71))
                .andExpect(jsonPath("$.tips[0].minutes").value(120));
    }

    @Test
    void aDayCannotBeClosedWhileSomeoneIsStillOnTheClock() throws Exception {
        String late = person("gec", Role.WAITER);
        mvc.perform(with(login(late), post("/api/v1/time-clock/in"))).andExpect(status().isOk());
        mvc.perform(as(ADMIN, json(post("/api/v1/tips/pool"), """
                        {"date": "%s"}""".formatted(today()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIP_POOL_STAFF_CLOCKED_IN"));
        mvc.perform(as(ADMIN, json(post("/api/v1/tips/pool"), """
                        {"date": "%s"}""".formatted(today().plusDays(2)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TIP_POOL_FUTURE"));
        mvc.perform(as(ADMIN, post("/api/v1/time-clock/users/{id}/out", idOf(late)))).andExpect(status().isOk());
    }

    /** Clocks the person in, then a manager sets the entry to hours [from, to) of the given day. */
    private void worked(String username, LocalDate day, int from, int to) throws Exception {
        mvc.perform(with(login(username), post("/api/v1/time-clock/in"))).andExpect(status().isOk());
        String entries = mvc.perform(as(ADMIN, get("/api/v1/time-clock/entries")
                        .param("from", today().toString()).param("to", today().toString())))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(entries, "$[?(@.username == '%s')].id".formatted(username));
        mvc.perform(as(ADMIN, json(put("/api/v1/time-clock/entries/{id}", ids.getFirst()), """
                        {"clockedInAt": "%s", "clockedOutAt": "%s", "reason": "Test hours"}"""
                        .formatted(day.atTime(from, 0).atZone(ISTANBUL).toInstant(), day.atTime(to, 0).atZone(ISTANBUL).toInstant()))))
                .andExpect(status().isOk());
    }

    private Map<String, BigDecimal> shares(String pool) {
        List<Map<String, Object>> list = JsonPath.read(pool, "$.shares");
        Map<String, BigDecimal> byName = new java.util.HashMap<>();
        for (Map<String, Object> share : list) {
            byName.put(userRepository.findById(((Number) share.get("userId")).longValue()).orElseThrow().getUsername(),
                    new BigDecimal(share.get("amount").toString()).setScale(2));
        }
        return byName;
    }

    private String person(String prefix, Role role) {
        String username = ("st-" + prefix + NUMBERS.incrementAndGet()).toLowerCase();
        userRepository.save(new AppUser(username, encoder.encode(username + "-pw"), prefix, role));
        return username;
    }

    private long idOf(String username) {
        return userRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username": "%s", "password": "%s-pw"}""".formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static LocalDate today() {
        return LocalDate.now(ISTANBUL);
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
