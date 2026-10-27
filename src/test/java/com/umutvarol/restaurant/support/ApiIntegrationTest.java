package com.umutvarol.restaurant.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.MenuItemRepository;
import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.security.Role;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for full-stack API tests: the real application context, the real Flyway schema and reference data,
 * and real JWTs obtained through the login endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiIntegrationTest {

    protected static final String ADMIN = "admin";
    protected static final String WAITER = "waiter";
    protected static final String CHEF = "chef";

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MenuItemRepository menuItems;
    @Autowired
    private DiningTableRepository tables;

    @BeforeEach
    void ensureStaffAccounts() {
        if (users.findByUsernameIgnoreCase(ADMIN).isEmpty()) {
            users.save(new AppUser(ADMIN, passwordEncoder.encode(ADMIN + "-pw"), "Test Manager", Role.ADMIN));
            users.save(new AppUser(WAITER, passwordEncoder.encode(WAITER + "-pw"), "Test Waiter", Role.WAITER));
            users.save(new AppUser(CHEF, passwordEncoder.encode(CHEF + "-pw"), "Test Chef", Role.KITCHEN));
        }
    }

    /** Signs in through the real login endpoint (once per user and JVM) and returns the access token. */
    protected String tokenFor(String username) throws Exception {
        String cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s-pw"}""".formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        TOKENS.put(username, token);
        return token;
    }

    protected MockHttpServletRequestBuilder as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(username));
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected long menuItemId(String name) {
        return menuItems.findByArchivedFalse().stream()
                .filter(item -> item.getName().equals(name))
                .map(MenuItem::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No menu item " + name));
    }

    protected long tableId(String code) {
        return tables.findAll().stream()
                .filter(table -> table.getCode().equals(code))
                .map(DiningTable::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No table " + code));
    }

    /** Places a takeaway order as the waiter and returns its id. */
    protected long placeTakeaway(String customer, String item, int quantity) throws Exception {
        String body = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "%s", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": %d}]}""".formatted(customer, menuItemId(item), quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** The id of the bill an order is on. */
    protected long billIdOf(long orderId) throws Exception {
        String bill = mvc.perform(as(WAITER, get("/api/v1/bills/by-order/{id}", orderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(bill, "$.id")).longValue();
    }

    /** Pays whatever is still due on the order's bill, as the waiter, and returns the bill id. */
    protected long payInFull(long orderId, String method) throws Exception {
        String bill = mvc.perform(as(WAITER, get("/api/v1/bills/by-order/{id}", orderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long billId = ((Number) JsonPath.read(bill, "$.id")).longValue();
        Object balance = JsonPath.read(bill, "$.balance");
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "%s", "amount": %s}""".formatted(method, balance)))
                .andExpect(status().isCreated());
        return billId;
    }
}
