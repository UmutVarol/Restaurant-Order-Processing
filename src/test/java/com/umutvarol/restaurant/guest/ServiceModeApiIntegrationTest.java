package com.umutvarol.restaurant.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The service mode decides which carts from a table go straight to the kitchen. Each cart comes from a new table of
 * its own (the pause between carts is per table), and each test leaves the settings as it found them: the context is
 * shared.
 */
class ServiceModeApiIntegrationTest extends ApiIntegrationTest {

    private static final String DEFAULTS = """
            {"mode": "MIXED", "autoAcceptLimit": 1500.00, "firstCartNeedsStaff": true}""";

    private final List<Long> ordersToCancel = new ArrayList<>();

    @AfterEach
    void leaveEverythingAsFound() throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/service-settings")), DEFAULTS)).andExpect(status().isOk());
        for (long orderId : ordersToCancel) {
            mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", orderId)), """
                    {"reason": "Test finished"}"""));
        }
        ordersToCancel.clear();
    }

    @Test
    void managersChooseTheModeAndStaffCanReadIt() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/service-settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MIXED"))
                .andExpect(jsonPath("$.autoAcceptLimit").value(1500.00))
                .andExpect(jsonPath("$.firstCartNeedsStaff").value(true));
        mvc.perform(json(as(WAITER, put("/api/v1/service-settings")), DEFAULTS)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/service-settings")).andExpect(status().isUnauthorized());
        mvc.perform(json(as(ADMIN, put("/api/v1/service-settings")), """
                        {"mode": "MIXED", "autoAcceptLimit": -1, "firstCartNeedsStaff": true}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(ADMIN, put("/api/v1/service-settings")), """
                        {"mode": "QR_FIRST", "autoAcceptLimit": null, "firstCartNeedsStaff": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("QR_FIRST"))
                .andExpect(jsonPath("$.updatedBy").value("admin"));
        // the guest's phone learns the mode with the table
        mvc.perform(get("/api/v1/public/table/{token}", issueQr(newTable())))
                .andExpect(jsonPath("$.mode").value("QR_FIRST"));
    }

    @Test
    void aCartFromASeatedTableGoesStraightToTheKitchenAndALargeOneWaits() throws Exception {
        String seated = newTable();
        seat(seated);
        String sent = sendCart(issueQr(seated), "Ayran", 2);
        assertThat((String) JsonPath.read(sent, "$.status")).isEqualTo("ACCEPTED");
        assertThat((Boolean) JsonPath.read(sent, "$.automatic")).isTrue();
        String active = mvc.perform(as(WAITER, get("/api/v1/orders/active")))
                .andReturn().getResponse().getContentAsString();
        List<Number> atTable = JsonPath.read(active, "$[?(@.table.code == '%s')].id".formatted(seated));
        assertThat(atTable).hasSize(2);
        ordersToCancel.add(atTable.getLast().longValue());
        List<String> placedBy = JsonPath.read(active, "$[?(@.table.code == '%s')].createdBy".formatted(seated));
        assertThat(placedBy).contains(GuestService.AUTO_ACTOR);
        assertThat(inboxIds(seated)).isEmpty();

        String other = newTable();
        seat(other);
        String token = issueQr(other);
        String large = sendCart(token, "Levrek Izgara", 3);
        assertThat((String) JsonPath.read(large, "$.status")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(large, "$.waitingFor")).isEqualTo("OVER_LIMIT");
        String inbox = mvc.perform(as(WAITER, get("/api/v1/guest-requests")))
                .andReturn().getResponse().getContentAsString();
        List<String> reasons = JsonPath.read(inbox, "$[?(@.tableCode == '%s')].holdReason".formatted(other));
        assertThat(reasons).containsExactly("OVER_LIMIT");
        long id = inboxIds(other).getFirst();
        String accepted = mvc.perform(as(WAITER, post("/api/v1/guest-requests/{id}/accept", id)))
                .andExpect(jsonPath("$.automatic").value(false))
                .andReturn().getResponse().getContentAsString();
        ordersToCancel.add(((Number) JsonPath.read(accepted, "$.orderId")).longValue());
        // the guest no longer sees a reason to wait
        mvc.perform(get("/api/v1/public/table/{token}/requests/{id}", token, JsonPath.read(large, "$.id")))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.waitingFor").doesNotExist());
    }

    @Test
    void classicModeHoldsEveryCartAndQrFirstOnlyHoldsDishesThatNeedAWaiter() throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/service-settings")), """
                {"mode": "CLASSIC", "autoAcceptLimit": null, "firstCartNeedsStaff": false}""")).andExpect(status().isOk());
        String classicTable = newTable();
        String classic = sendCart(issueQr(classicTable), "Ayran", 1);
        assertThat((String) JsonPath.read(classic, "$.waitingFor")).isEqualTo("CLASSIC_MODE");
        reject(classicTable);

        mvc.perform(json(as(ADMIN, put("/api/v1/service-settings")), """
                {"mode": "QR_FIRST", "autoAcceptLimit": null, "firstCartNeedsStaff": true}""")).andExpect(status().isOk());
        // nobody is seated at the table, yet the first cart goes to the kitchen
        String empty = newTable();
        String first = sendCart(issueQr(empty), "Mercimek Çorbası", 1);
        assertThat((String) JsonPath.read(first, "$.status")).isEqualTo("ACCEPTED");
        String active = mvc.perform(as(WAITER, get("/api/v1/orders/active")))
                .andReturn().getResponse().getContentAsString();
        List<Number> placed = JsonPath.read(active, "$[?(@.table.code == '%s')].id".formatted(empty));
        assertThat(placed).hasSize(1);
        ordersToCancel.addAll(placed.stream().map(Number::longValue).toList());

        long salgam = menuItemId("Şalgam");
        String item = mvc.perform(get("/api/v1/menu/{id}", salgam)).andReturn().getResponse().getContentAsString();
        setNeedsApproval(salgam, item, true);
        try {
            mvc.perform(get("/api/v1/menu/{id}", salgam)).andExpect(jsonPath("$.needsApproval").value(true));
            String approvalTable = newTable();
            String held = sendCart(issueQr(approvalTable), "Şalgam", 1);
            assertThat((String) JsonPath.read(held, "$.waitingFor")).isEqualTo("NEEDS_APPROVAL");
            reject(approvalTable);
        } finally {
            setNeedsApproval(salgam, item, false);
        }
    }

    private void seat(String table) throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Seated", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(tableId(table), menuItemId("Demlik Çay"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ordersToCancel.add(((Number) JsonPath.read(created, "$.id")).longValue());
    }

    private String sendCart(String token, String item, int quantity) throws Exception {
        return mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"items": [{"menuItemId": %d, "quantity": %d}]}""".formatted(menuItemId(item), quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Long> inboxIds(String table) throws Exception {
        String inbox = mvc.perform(as(WAITER, get("/api/v1/guest-requests")))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(inbox, "$[?(@.tableCode == '%s' && @.kind == 'ORDER')].id".formatted(table));
        return ids.stream().map(Number::longValue).toList();
    }

    private void reject(String table) throws Exception {
        for (long id : inboxIds(table)) {
            mvc.perform(json(as(WAITER, post("/api/v1/guest-requests/{id}/reject", id)), """
                    {"reason": "Test finished"}""")).andExpect(status().isOk());
        }
    }

    private void setNeedsApproval(long id, String item, boolean needed) throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/menu/{id}", id)), """
                        {"name": "%s", "description": "%s", "category": "%s", "price": %s, "icon": "%s",
                         "prepTimeMinutes": %s, "popular": %s, "tags": %s, "needsApproval": %s}""".formatted(
                        JsonPath.read(item, "$.name"), JsonPath.read(item, "$.description"),
                        JsonPath.read(item, "$.category"), JsonPath.read(item, "$.price"),
                        JsonPath.read(item, "$.icon"), JsonPath.read(item, "$.prepTimeMinutes"),
                        JsonPath.read(item, "$.popular"),
                        JsonPath.parse(JsonPath.<Object>read(item, "$.tags")).jsonString(), needed)))
                .andExpect(status().isOk());
    }

    private String issueQr(String table) throws Exception {
        String body = mvc.perform(as(ADMIN, post("/api/v1/tables/{id}/qr", tableId(table))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
