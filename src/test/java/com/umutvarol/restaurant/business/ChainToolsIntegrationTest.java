package com.umutvarol.restaurant.business;

import static org.hamcrest.Matchers.hasItem;
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

/**
 * The Chain plan's central menu with each branch's own prices and sales: what a branch's till, its tables' QR menu
 * and its guests' carts show is what they are charged; the other branches keep the central menu. The owner sees the
 * branches side by side.
 */
class ChainToolsIntegrationTest extends ApiIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Test
    void aBranchSellsTheCentralMenuAtItsOwnPricesAndTheOwnerComparesTheBranches() throws Exception {
        String manager = login(ADMIN, ADMIN + "-pw");
        long main = id(mvc.perform(with(manager, get("/api/v1/branches"))).andReturn().getResponse().getContentAsString(), "$[0].id");
        long branch = id(mvc.perform(with(manager, json(post("/api/v1/branches"), """
                        {"code": "zincir-%d", "name": "Moda"}""".formatted(NUMBERS.incrementAndGet()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        long ayran = menuItemId("Ayran");
        long lahmacun = menuItemId("Lahmacun");
        try {
            String central = JsonPath.read(mvc.perform(get("/api/v1/menu/{id}", ayran)).andReturn().getResponse().getContentAsString(), "$.price").toString();
            mvc.perform(with(manager, json(put("/api/v1/menu/{id}/branches/{branch}", ayran, branch), """
                            {"price": 55.50}""")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.branchId == %d)].price".formatted(branch)).value(55.5));
            mvc.perform(with(manager, json(put("/api/v1/menu/{id}/branches/{branch}", lahmacun, branch), """
                            {"available": false}"""))).andExpect(status().isOk());
            // the price list itself is for managers only
            mvc.perform(get("/api/v1/menu/{id}/branches", ayran)).andExpect(status().isUnauthorized());

            // the branch's till sees and charges its own price; Lahmacun is not sold there
            mvc.perform(in(branch, with(manager, get("/api/v1/menu"))))
                    .andExpect(jsonPath("$[?(@.id == %d)].price".formatted(ayran)).value(55.5))
                    .andExpect(jsonPath("$[?(@.id == %d)].available".formatted(lahmacun)).value(false));
            long table = id(mvc.perform(in(branch, with(manager, json(post("/api/v1/tables"), """
                            {"code": "Z%d", "seats": 2, "zone": "INDOOR"}""".formatted(NUMBERS.get())))))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
            String order = mvc.perform(in(branch, with(manager, json(post("/api/v1/orders"), """
                            {"type": "DINE_IN", "customerName": "Moda", "tableId": %d, "paymentMethod": "CASH",
                             "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(table, ayran)))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines[0].unitPrice").value(55.5))
                    .andReturn().getResponse().getContentAsString();
            mvc.perform(in(branch, with(manager, json(post("/api/v1/orders"), """
                            {"type": "DINE_IN", "customerName": "Moda", "tableId": %d, "paymentMethod": "CASH",
                             "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(table, lahmacun)))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("MENU_ITEM_UNAVAILABLE"));
            // the main branch keeps the central menu
            mvc.perform(in(main, with(manager, get("/api/v1/menu"))))
                    .andExpect(jsonPath("$[?(@.id == %d)].price".formatted(ayran)).value(Double.parseDouble(central)));

            // a guest at the branch's table sees the branch's menu and is quoted its price
            String token = JsonPath.read(mvc.perform(with(manager, post("/api/v1/tables/{id}/qr", table)))
                    .andReturn().getResponse().getContentAsString(), "$.token");
            mvc.perform(get("/api/v1/public/table/{token}/menu", token))
                    .andExpect(jsonPath("$[?(@.id == %d)].price".formatted(ayran)).value(55.5))
                    .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.not(hasItem((int) lahmacun))));
            mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                            {"items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(ayran)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.total").value(55.50)); // the branch's own price, VAT included

            // the owner sees the branches side by side; a branch manager does not
            mvc.perform(with(manager, get("/api/v1/analytics/branches").param("days", "1")))
                    .andExpect(status().isOk())
                    // the till's order and the guest's cart, both in the branch
                    .andExpect(jsonPath("$.branches[?(@.branchId == %d)].orders".formatted(branch)).value(2))
                    .andExpect(jsonPath("$.branches[*].branchId", hasItem((int) main)));

            // back to the central menu
            mvc.perform(with(manager, json(put("/api/v1/menu/{id}/branches/{branch}", ayran, branch), "{}")))
                    .andExpect(jsonPath("$[?(@.branchId == %d)].price".formatted(branch)).value(org.hamcrest.Matchers.contains((Object) null)));
            mvc.perform(in(branch, with(manager, get("/api/v1/menu"))))
                    .andExpect(jsonPath("$[?(@.id == %d)].price".formatted(ayran)).value(Double.parseDouble(central)));

            java.util.List<Integer> open = JsonPath.read(mvc.perform(in(branch, with(manager, get("/api/v1/orders/active"))))
                    .andReturn().getResponse().getContentAsString(), "$[*].id");
            for (int id : open) {
                mvc.perform(in(branch, with(manager, json(post("/api/v1/orders/{id}/cancel", id), """
                                {"reason": "Test finished"}"""))));
            }
            assert open.contains((int) id(order, "$.id"));
            mvc.perform(with(manager, json(put("/api/v1/menu/{id}/branches/{branch}", lahmacun, branch), "{}"))).andExpect(status().isOk());
        } finally {
            mvc.perform(with(manager, json(put("/api/v1/branches/{id}", branch), """
                            {"name": "Moda", "active": false}"""))).andExpect(status().isOk());
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
