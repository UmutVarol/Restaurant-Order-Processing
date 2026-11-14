package com.umutvarol.restaurant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StaffApiIntegrationTest extends ApiIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Test
    void aNewWaiterChoosesTheirOwnPasswordBeforeDoingAnything() throws Exception {
        String manager = freshManagerToken();
        String username = "garson" + NUMBERS.incrementAndGet();
        String created = mvc.perform(with(manager, json(post("/api/v1/staff"), """
                        {"username": "%s", "displayName": "Yeni Garson", "role": "WAITER"}""".formatted(username))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.staff.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        assertThat(temporary).matches("[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}");

        String first = login(username, temporary);
        // only the password change, "who am I" and signing out are open to this session
        mvc.perform(with(first, get("/api/v1/orders/active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(with(first, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // weak choices are refused
        for (String[] weak : new String[][] {{"short1", "PASSWORD_TOO_SHORT"}, {"restaurant123", "PASSWORD_TOO_COMMON"},
                {username + "-2026!", "PASSWORD_CONTAINS_USERNAME"}, {"aaaaaaaaaaaa", "PASSWORD_TOO_COMMON"}}) {
            mvc.perform(with(first, json(post("/api/v1/auth/password"), """
                            {"currentPassword": "%s", "newPassword": "%s"}""".formatted(temporary, weak[0]))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value(weak[1]));
        }
        String changed = mvc.perform(with(first, json(post("/api/v1/auth/password"), """
                        {"currentPassword": "%s", "newPassword": "kahve ve simit 7"}""".formatted(temporary))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(changed, "$.accessToken");
        mvc.perform(with(fresh, get("/api/v1/orders/active"))).andExpect(status().isOk());
        login(username, "kahve ve simit 7");
    }

    @Test
    void switchingAnAccountOffEndsItsSessionsAtOnce() throws Exception {
        String manager = freshManagerToken();
        String username = "komi" + NUMBERS.incrementAndGet();
        long id = createAndActivate(manager, username, "WAITER");
        String session = login(username, "masa dört hazır 9");
        mvc.perform(with(session, get("/api/v1/orders/active"))).andExpect(status().isOk());

        mvc.perform(with(manager, json(put("/api/v1/staff/{id}", id), """
                        {"displayName": "Eski Komi", "role": "WAITER", "enabled": false}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(with(session, get("/api/v1/orders/active"))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "%s", "password": "masa dört hazır 9"}""".formatted(username)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleChangeOrPasswordResetEndsOldSessions() throws Exception {
        String manager = freshManagerToken();
        String username = "mutfak" + NUMBERS.incrementAndGet();
        long id = createAndActivate(manager, username, "WAITER");
        String session = login(username, "masa dört hazır 9");

        mvc.perform(with(manager, json(put("/api/v1/staff/{id}", id), """
                        {"displayName": "Aşçı", "role": "KITCHEN", "enabled": true}""")))
                .andExpect(status().isOk());
        mvc.perform(with(session, get("/api/v1/orders/active"))).andExpect(status().isUnauthorized());

        String kitchen = login(username, "masa dört hazır 9");
        String reset = mvc.perform(with(manager, post("/api/v1/staff/{id}/reset-password", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(with(kitchen, get("/api/v1/orders/active"))).andExpect(status().isUnauthorized());
        login(username, JsonPath.read(reset, "$.temporaryPassword"));
    }

    @Test
    void aManagerCannotLockThemselvesOutAndOnlyManagersManageStaff() throws Exception {
        String manager = freshManagerToken();
        String list = mvc.perform(with(manager, get("/api/v1/staff")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(ADMIN)))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Integer> ids = JsonPath.read(list, "$[?(@.username == '%s')].id".formatted(ADMIN));
        int self = ids.getFirst();
        mvc.perform(with(manager, json(put("/api/v1/staff/{id}", self), """
                        {"displayName": "Test Manager", "role": "WAITER", "enabled": true}""")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STAFF_SELF_LOCKOUT"));
        mvc.perform(with(manager, post("/api/v1/staff/{id}/reset-password", self)))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(as(WAITER, get("/api/v1/staff"))).andExpect(status().isForbidden());
        mvc.perform(as(WAITER, json(post("/api/v1/staff"), """
                        {"username": "sneaky", "displayName": "Sneaky", "role": "ADMIN"}"""))).andExpect(status().isForbidden());

        mvc.perform(with(manager, json(post("/api/v1/staff"), """
                        {"username": "%s", "displayName": "Duplicate", "role": "WAITER"}""".formatted(ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        mvc.perform(with(manager, json(post("/api/v1/staff"), """
                        {"username": "Bad Name!", "displayName": "X", "role": "WAITER"}""")))
                .andExpect(status().isBadRequest());

        long id = createAndActivate(manager, "pinli" + NUMBERS.incrementAndGet(), "WAITER");
        mvc.perform(with(manager, delete("/api/v1/staff/{id}/pin", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPin").value(false));
    }

    /** Creates an account and replaces its temporary password, as the employee would at the first sign-in. */
    private long createAndActivate(String manager, String username, String role) throws Exception {
        String created = mvc.perform(with(manager, json(post("/api/v1/staff"), """
                        {"username": "%s", "displayName": "%s", "role": "%s"}""".formatted(username, username, role))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        mvc.perform(with(login(username, temporary), json(post("/api/v1/auth/password"), """
                        {"currentPassword": "%s", "newPassword": "masa dört hazır 9"}""".formatted(temporary))))
                .andExpect(status().isOk());
        return ((Number) JsonPath.read(created, "$.staff.id")).longValue();
    }

    /** A manager token issued just now, so actions needing a recent sign-in pass. */
    private String freshManagerToken() throws Exception {
        return login(ADMIN, ADMIN + "-pw");
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username": "%s", "password": "%s"}""".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
