package com.umutvarol.restaurant.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guests order and call from the table's QR code; nothing reaches the kitchen until a waiter accepts it. */
class GuestOrderingApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void aCartWaitsForAWaiterAndBecomesARealOrder() throws Exception {
        String token = issueQr("A1");
        mvc.perform(get("/api/v1/public/table/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCode").value("A1"))
                .andExpect(jsonPath("$.restaurant").value("Sofra Bistro"));

        String sent = mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"guestName": "Deniz", "note": "No onions please",
                         "items": [{"menuItemId": %d, "quantity": 2}, {"menuItemId": %d, "quantity": 1}]}"""
                        .formatted(menuItemId("Lahmacun"), menuItemId("Demlik Çay"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.total").value(533.50))
                .andExpect(jsonPath("$.lines[0].options").value("Acısız"))
                .andReturn().getResponse().getContentAsString();
        String publicId = JsonPath.read(sent, "$.id");

        // a second cart straight away is too soon
        mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(menuItemId("Ayran"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GUEST_SLOW_DOWN"));

        String inbox = mvc.perform(as(WAITER, get("/api/v1/guest-requests")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(inbox, "$[?(@.tableCode == 'A1' && @.kind == 'ORDER')].id");
        long id = ids.getLast().longValue();

        String handled = mvc.perform(as(WAITER, post("/api/v1/guest-requests/{id}/accept", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.handledBy").value("waiter"))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(handled, "$.orderId")).longValue();
        String accepted = mvc.perform(get("/api/v1/public/table/{token}/requests/{id}", token, publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accepted).doesNotContain("waiter");

        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", orderId)))
                .andExpect(jsonPath("$.table.code").value("A1"))
                .andExpect(jsonPath("$.customerName").value("Deniz"))
                .andExpect(jsonPath("$.note").value("No onions please"))
                .andExpect(jsonPath("$.pricing.total").value(533.50));

        // accepting twice is refused
        mvc.perform(as(ADMIN, post("/api/v1/guest-requests/{id}/accept", id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GUEST_REQUEST_HANDLED"));

        // leave the table free: the test context (and its tables) is shared with the payment tests
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", orderId)), """
                        {"reason": "Test finished"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void callsFoldIntoTheOneAlreadyWaitingAndARejectedCartTellsTheGuestWhy() throws Exception {
        String token = issueQr("W1");
        String first = mvc.perform(json(post("/api/v1/public/table/{token}/calls", token), """
                        {"kind": "CALL_WAITER"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String again = mvc.perform(json(post("/api/v1/public/table/{token}/calls", token), """
                        {"kind": "CALL_WAITER"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        mvc.perform(get("/api/v1/public/table/{token}", token))
                .andExpect(jsonPath("$.openCalls[0]").value("CALL_WAITER"));
        mvc.perform(json(post("/api/v1/public/table/{token}/calls", token), """
                        {"kind": "ORDER"}"""))
                .andExpect(status().isUnprocessableContent());

        String sent = mvc.perform(json(post("/api/v1/public/table/{token}/orders", token), """
                        {"items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(menuItemId("Künefe"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String inbox = mvc.perform(as(WAITER, get("/api/v1/guest-requests")))
                .andReturn().getResponse().getContentAsString();
        List<Number> calls = JsonPath.read(inbox, "$[?(@.tableCode == 'W1' && @.kind == 'CALL_WAITER')].id");
        List<Number> carts = JsonPath.read(inbox, "$[?(@.tableCode == 'W1' && @.kind == 'ORDER')].id");
        mvc.perform(as(WAITER, post("/api/v1/guest-requests/{id}/done", calls.getFirst().longValue())))
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(json(as(WAITER, post("/api/v1/guest-requests/{id}/reject", carts.getFirst().longValue())), """
                        {"reason": "Künefe is finished for today"}"""))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/v1/public/table/{token}/requests/{id}", token, JsonPath.read(sent, "$.id")))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Künefe is finished for today"));
    }

    @Test
    void aReplacedOrMadeUpCodeOpensNothing() throws Exception {
        String old = issueQr("A2");
        issueQr("A2");
        mvc.perform(get("/api/v1/public/table/{token}", old)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/table/{token}", "x".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/table/{token}", "short")).andExpect(status().isBadRequest());
        // another table's token cannot read this table's requests
        String token = issueQr("A3");
        String sent = mvc.perform(json(post("/api/v1/public/table/{token}/calls", token), """
                        {"kind": "REQUEST_BILL"}"""))
                .andReturn().getResponse().getContentAsString();
        String other = issueQr("T4");
        mvc.perform(get("/api/v1/public/table/{token}/requests/{id}", other, JsonPath.read(sent, "$.id")))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyManagersIssueCodesAndOnlyTillStaffSeeTheInbox() throws Exception {
        mvc.perform(as(WAITER, post("/api/v1/tables/{id}/qr", tableId("A1")))).andExpect(status().isForbidden());
        mvc.perform(as(CHEF, get("/api/v1/guest-requests"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/guest-requests")).andExpect(status().isUnauthorized());
    }

    private String issueQr(String table) throws Exception {
        String body = mvc.perform(as(ADMIN, post("/api/v1/tables/{id}/qr", tableId(table))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCode").value(table))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
