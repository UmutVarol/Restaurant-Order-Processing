package com.umutvarol.restaurant.business;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A chain's branches each keep their own floor, orders and people; a branch worker never sees another branch. */
class BranchIsolationIntegrationTest extends ApiIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Test
    void eachBranchKeepsItsOwnFloorAndOrdersAndABranchWorkerStaysInTheirs() throws Exception {
        String manager = login(ADMIN, ADMIN + "-pw");
        long main = id(mvc.perform(with(manager, get("/api/v1/branches")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        String code = "sube-" + NUMBERS.incrementAndGet();
        long branch = id(mvc.perform(with(manager, json(post("/api/v1/branches"), """
                        {"code": "%s", "name": "Kadıköy"}""".formatted(code))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(with(manager, json(post("/api/v1/branches"), """
                        {"code": "%s", "name": "Again"}""".formatted(code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BRANCH_CODE_TAKEN"));

        try {
            exercise(manager, main, branch);
        } finally {
            // closed even when a check fails, so no other test finds a second open branch
            mvc.perform(with(manager, json(put("/api/v1/branches/{id}", branch), """
                            {"name": "Kadıköy", "active": false}"""))).andExpect(status().isOk());
        }
    }

    private void exercise(String manager, long main, long branch) throws Exception {
        // table 1 exists in the main branch; the new branch has a table 1 of its own
        long table = id(mvc.perform(in(branch, with(manager, json(post("/api/v1/tables"), """
                        {"code": "1", "seats": 2, "zone": "INDOOR"}"""))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(in(branch, with(manager, get("/api/v1/floor/tables"))))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) table));
        mvc.perform(in(main, with(manager, get("/api/v1/floor/tables"))))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) table))));

        // an order taken in the branch is that branch's
        long order = id(mvc.perform(in(branch, with(manager, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "Kadıköy", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(table, menuItemId("Ayran"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(in(branch, with(manager, get("/api/v1/orders/active"))))
                .andExpect(jsonPath("$[*].id", hasItem((int) order)));
        mvc.perform(in(main, with(manager, get("/api/v1/orders/active"))))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) order))));
        // from the main branch it does not exist, even asked for by id
        mvc.perform(in(main, with(manager, get("/api/v1/orders/{id}", order)))).andExpect(status().isNotFound());
        // the owner, working in all branches, sees everything
        mvc.perform(with(manager, get("/api/v1/orders/active"))).andExpect(jsonPath("$[*].id", hasItem((int) order)));

        // what is kept per branch and day needs a branch once there are two
        mvc.perform(with(manager, get("/api/v1/payments/day")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BRANCH_REQUIRED"));
        mvc.perform(in(branch, with(manager, get("/api/v1/payments/day")))).andExpect(status().isOk());

        // a manager of the branch only: their token holds the branch, a header cannot take them elsewhere
        String username = "br-mgr" + NUMBERS.get();
        String created = mvc.perform(with(manager, json(post("/api/v1/staff"), """
                        {"username": "%s", "displayName": "Şube Müdürü", "role": "ADMIN", "branchId": %d}"""
                        .formatted(username, branch))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staff.branchId").value((int) branch))
                .andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        String changed = mvc.perform(with(login(username, temporary), json(post("/api/v1/auth/password"), """
                        {"currentPassword": "%s", "newPassword": "kadikoy iskelesi 9"}""".formatted(temporary))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String local = JsonPath.read(changed, "$.accessToken");

        mvc.perform(with(local, get("/api/v1/branches")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) branch));
        mvc.perform(in(main, with(local, get("/api/v1/floor/tables"))))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) table));
        // another branch's table cannot be changed, even named by id
        mvc.perform(with(local, json(put("/api/v1/tables/{id}", tableId("2")), """
                        {"code": "2", "seats": 9, "zone": "INDOOR"}"""))).andExpect(status().isNotFound());
        mvc.perform(with(local, json(post("/api/v1/branches"), """
                        {"code": "baska", "name": "Başka"}""")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_MANAGER_ONLY"));
        // nor can they make anyone a worker of all branches, themselves included
        mvc.perform(with(local, json(post("/api/v1/staff"), """
                        {"username": "%s-2", "displayName": "Kaçak", "role": "WAITER", "branchId": %d}"""
                        .formatted(username, main))))
                .andExpect(status().isForbidden());

        // a legal receipt is its branch's: the branch's manager sees it, another branch does not. Served and paid,
        // the bill closes and gets its receipt.
        for (int step = 0; step < 3; step++) {
            mvc.perform(in(branch, with(manager, post("/api/v1/orders/{id}/advance", order)))).andExpect(status().isOk());
        }
        String bill = mvc.perform(in(branch, with(manager, get("/api/v1/bills/by-order/{id}", order))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long billId = id(bill, "$.id");
        mvc.perform(in(branch, with(manager, json(post("/api/v1/bills/{id}/payments", billId), """
                        {"method": "CASH", "amount": %s}""".formatted(JsonPath.<Object>read(bill, "$.balance"))))))
                .andExpect(status().isCreated());
        mvc.perform(with(local, get("/api/v1/fiscal/documents")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].billId", hasItem((int) billId)));
        mvc.perform(in(main, with(manager, get("/api/v1/fiscal/documents"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].billId", not(hasItem((int) billId))));

        mvc.perform(with(manager, delete("/api/v1/tables/{id}", table))).andExpect(status().isNoContent());
    }

    @Test
    void theLastOpenBranchStaysOpen() throws Exception {
        String manager = login(ADMIN, ADMIN + "-pw");
        String list = mvc.perform(with(manager, get("/api/v1/branches"))).andReturn().getResponse().getContentAsString();
        long main = id(list, "$[0].id");
        java.util.List<Integer> open = JsonPath.read(list, "$[?(@.active == true)].id");
        if (open.size() == 1) {
            mvc.perform(with(manager, json(put("/api/v1/branches/{id}", main), """
                            {"name": "Merkez", "active": false}""")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("LAST_BRANCH"));
        }
    }

    private static MockHttpServletRequestBuilder in(long branch, MockHttpServletRequestBuilder request) {
        return request.header(BusinessFilter.BRANCH_HEADER, String.valueOf(branch));
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username": "%s", "password": "%s"}""".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static long id(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }
}
