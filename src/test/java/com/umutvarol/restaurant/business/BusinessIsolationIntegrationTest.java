package com.umutvarol.restaurant.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sofra Cloud: two businesses on one server, and neither can see, change or even learn about the other's data,
 * through any endpoint, guest link or live event (docs/adr/0001-multi-business.md). Runs with the strict setting, in
 * which work that does not say which business it is for is refused.
 */
@SpringBootTest(properties = {"restaurant.cloud.enabled=true", "restaurant.cloud.signup-enabled=true",
        "restaurant.cloud.base-domain=sofra.test", "restaurant.demo.enabled=false",
        "restaurant.security.rate-limit.public-requests-per-minute=100000",
        "restaurant.cloud.signups-per-client-per-hour=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessIsolationIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    record Opened(String address, String token) {
    }

    @Test
    void twoBusinessesNeverSeeOrTouchEachOthersData() throws Exception {
        Opened kebab = open("Kebapçı");
        Opened cafe = open("Kafe");

        // each has its own sample tables and dishes; the same table numbers, not the same tables
        String kebabTables = mvc.perform(with(kebab, get("/api/v1/tables"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cafeTables = mvc.perform(with(cafe, get("/api/v1/tables"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> kebabIds = JsonPath.read(kebabTables, "$[*].id");
        List<Integer> cafeIds = JsonPath.read(cafeTables, "$[*].id");
        assertThat(kebabIds).hasSize(12).doesNotContainAnyElementsOf(cafeIds);

        // the kebab shop renames a dish and takes an order
        String kebabMenu = mvc.perform(with(kebab, get("/api/v1/menu"))).andReturn().getResponse().getContentAsString();
        int dish = ((List<Integer>) JsonPath.read(kebabMenu, "$[?(@.name == 'Ayran')].id")).getFirst();
        String order = mvc.perform(with(kebab, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "Gizli Müşteri", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(kebabIds.getFirst(), dish))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();

        // the café sees none of it, by list or by id, and cannot change it
        mvc.perform(with(cafe, get("/api/v1/orders/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].customerName", not(hasItem("Gizli Müşteri"))));
        mvc.perform(with(cafe, get("/api/v1/orders/{id}", orderId))).andExpect(status().isNotFound());
        mvc.perform(with(cafe, post("/api/v1/orders/{id}/advance", orderId))).andExpect(status().isNotFound());
        mvc.perform(with(cafe, get("/api/v1/bills/by-order/{id}", orderId))).andExpect(status().isNotFound());
        mvc.perform(with(cafe, get("/api/v1/floor/tables")))
                .andExpect(jsonPath("$[*].occupied", everyItem(org.hamcrest.Matchers.is(false))));
        // nor order onto the other's table, or with the other's dish
        mvc.perform(with(cafe, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "x", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(kebabIds.getFirst(), dish))))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(with(cafe, json(put("/api/v1/tables/{id}", kebabIds.get(1)), """
                        {"code": "99", "seats": 2, "zone": "INDOOR"}"""))).andExpect(status().isNotFound());
        mvc.perform(with(cafe, delete("/api/v1/tables/{id}", kebabIds.get(1)))).andExpect(status().isNotFound());

        // staff lists are separate, and the same username can exist in both
        mvc.perform(with(cafe, get("/api/v1/staff")))
                .andExpect(jsonPath("$[*].username", not(hasItem(kebab.address() + "-owner"))));
        mvc.perform(with(kebab, json(post("/api/v1/staff"), """
                        {"username": "ayse", "displayName": "Ayşe", "role": "WAITER"}"""))).andExpect(status().isCreated());
        mvc.perform(with(cafe, json(post("/api/v1/staff"), """
                        {"username": "ayse", "displayName": "Ayşe", "role": "WAITER"}"""))).andExpect(status().isCreated());

        // reservations and the time clock too
        mvc.perform(with(kebab, json(post("/api/v1/reservations/walk-ins"), """
                        {"guestName": "Kebap Ailesi", "partySize": 4}"""))).andExpect(status().isCreated());
        mvc.perform(with(cafe, get("/api/v1/reservations")))
                .andExpect(jsonPath("$[*].guestName", not(hasItem("Kebap Ailesi"))));
    }

    @Test
    void signingInNeedsTheRightBusinessAndATokenOnlyWorksInItsOwn() throws Exception {
        Opened one = open("Lokanta");
        Opened two = open("Meyhane");
        String password = "masa dört hazır 9";

        // the owner signs in at their own address, or with their business code; never at the other
        mvc.perform(json(post("/api/v1/auth/login").header(HttpHeaders.HOST, one.address() + ".sofra.test"), """
                        {"username": "%s-owner", "password": "%s"}""".formatted(one.address(), password)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/auth/login").header("X-Business", one.address()), """
                        {"username": "%s-owner", "password": "%s"}""".formatted(one.address(), password)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/auth/login").header("X-Business", two.address()), """
                        {"username": "%s-owner", "password": "%s"}""".formatted(one.address(), password)))
                .andExpect(status().isUnauthorized());
        // without saying which business, a server with several cannot sign anyone in
        mvc.perform(json(post("/api/v1/auth/login"), """
                        {"username": "%s-owner", "password": "%s"}""".formatted(one.address(), password)))
                .andExpect(status().isUnauthorized());

        // the sign-in screen tells only the name of the business the address names
        mvc.perform(get("/api/v1/public/config").header("X-Business", two.address()))
                .andExpect(jsonPath("$.restaurantName").value("Meyhane " + two.address().replaceAll("^.*-", "")))
                .andExpect(jsonPath("$.businessCodeNeeded").value(false));
        mvc.perform(get("/api/v1/public/config"))
                .andExpect(jsonPath("$.restaurantName").doesNotExist())
                .andExpect(jsonPath("$.businessCodeNeeded").value(true));

        // a token pointed at the other business's address still works only in its own
        mvc.perform(with(one, get("/api/v1/staff")).header("X-Business", two.address()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(one.address() + "-owner")))
                .andExpect(jsonPath("$[*].username", not(hasItem(two.address() + "-owner"))));
    }

    @Test
    void aGuestsQrCodeLeadsOnlyToItsOwnBusiness() throws Exception {
        Opened one = open("Pide");
        Opened two = open("Burger");
        String tables = mvc.perform(with(one, get("/api/v1/tables"))).andReturn().getResponse().getContentAsString();
        long table = ((Number) JsonPath.read(tables, "$[0].id")).longValue();
        String qr = mvc.perform(with(one, post("/api/v1/tables/{id}/qr", table))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(qr, "$.token");

        mvc.perform(get("/api/v1/public/table/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurant").value(org.hamcrest.Matchers.startsWith("Pide")));
        String menu = mvc.perform(get("/api/v1/menu").header("X-Business", one.address())).andReturn().getResponse().getContentAsString();
        int dish = ((List<Integer>) JsonPath.read(menu, "$[?(@.name == 'Çay')].id")).getFirst();
        mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"guestName": "Misafir", "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(dish)))
                .andExpect(status().is2xxSuccessful());
        // the cart reached the right inbox, and only that one
        mvc.perform(with(one, get("/api/v1/guest-requests")))
                .andExpect(jsonPath("$[*].guestName", hasItem("Misafir")));
        mvc.perform(with(two, get("/api/v1/guest-requests")))
                .andExpect(jsonPath("$[*].guestName", not(hasItem("Misafir"))));
        // a guest cannot order the other business's dish through this table
        String otherMenu = mvc.perform(get("/api/v1/menu").header("X-Business", two.address())).andReturn().getResponse().getContentAsString();
        int otherDish = ((List<Integer>) JsonPath.read(otherMenu, "$[?(@.name == 'Çay')].id")).getFirst();
        assertThat(otherDish).isNotEqualTo(dish);
        mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"guestName": "Misafir", "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(otherDish)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void signUpChecksTheAddressAndTheOwnersPassword() throws Exception {
        mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "Admin", "address": "admin", "email": "a@example.com", "ownerName": "A",
                         "username": "owner", "password": "masa dört hazır 9"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_ADDRESS_INVALID"));
        Opened taken = open("Çorbacı");
        mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "Başka", "address": "%s", "email": "a@example.com", "ownerName": "A",
                         "username": "owner", "password": "masa dört hazır 9"}""".formatted(taken.address())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ADDRESS_TAKEN"));
        mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "Zayıf Şifre", "email": "a@example.com", "ownerName": "A",
                         "username": "owner", "password": "123"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
        // the failed sign-up left no half-made business behind: the address is still free
        mvc.perform(get("/api/v1/public/signup/address").param("name", "Zayıf Şifre"))
                .andExpect(jsonPath("$.address").value("zayif-sifre"))
                .andExpect(jsonPath("$.free").value(true));
    }

    private Opened open(String name) throws Exception {
        String unique = name + " " + NUMBERS.incrementAndGet();
        String address = BusinessRegistry.suggestAddress(unique);
        String body = mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "%s", "email": "owner@example.com", "ownerName": "Sahibi",
                         "username": "%s-owner", "password": "masa dört hazır 9"}""".formatted(unique, address)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value(address))
                .andReturn().getResponse().getContentAsString();
        return new Opened(address, JsonPath.read(body, "$.session.accessToken"));
    }

    private static MockHttpServletRequestBuilder with(Opened business, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + business.token());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
