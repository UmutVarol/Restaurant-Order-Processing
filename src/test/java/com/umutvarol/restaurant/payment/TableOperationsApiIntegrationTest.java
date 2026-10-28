package com.umutvarol.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Voids, comps, manual discounts with manager approval, and moving and merging tables. */
class TableOperationsApiIntegrationTest extends ApiIntegrationTest {

    private static final String MANAGER_PIN = "7302";

    @BeforeEach
    void managerHasAPin() throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/auth/pin")), """
                        {"password": "admin-pw", "pin": "%s"}""".formatted(MANAGER_PIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void aWaiterVoidsFreelyBeforeTheKitchenStartsAndNeedsAManagerAfter() throws Exception {
        String order = placeDineIn("A4", "Lahmacun", 3);
        long id = id(order);
        long line = lineId(order);

        // still PENDING: the waiter corrects the ticket alone
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)), """
                        {"kind": "VOID", "quantity": 1, "reason": "Typed 3, meant 2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].voidedQuantity").value(1))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(440.00))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.pricing.total").value(484.00))
                .andExpect(jsonPath("$.adjustments[0].kind").value("VOID"))
                .andExpect(jsonPath("$.adjustments[0].approvedBy").doesNotExist());

        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/advance", id))).andExpect(status().isOk());
        String voidOne = """
                {"kind": "VOID", "quantity": 1, "reason": "Guest changed mind"%s}""";
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)), voidOne.formatted("")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)),
                        voidOne.formatted(", \"approval\": {\"manager\": \"admin\", \"pin\": \"0101\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_DENIED"));
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)),
                        voidOne.formatted(", \"approval\": {\"manager\": \"admin\", \"pin\": \"%s\"}".formatted(MANAGER_PIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].voidedQuantity").value(2))
                .andExpect(jsonPath("$.adjustments[1].approvedBy").value("admin"))
                .andExpect(jsonPath("$.adjustments[1].by").value("waiter"));

        // the last unit cannot be voided: that is a cancellation
        mvc.perform(json(as(ADMIN, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)), """
                        {"kind": "VOID", "quantity": 1, "reason": "x"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VOID_LAST_ITEM"));
        mvc.perform(json(as(ADMIN, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)), """
                        {"kind": "COMP", "quantity": 2, "reason": "x"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ADJUST_QUANTITY_INVALID"));
    }

    @Test
    void aCompAndADiscountAreRepricedLikeANewOrder() throws Exception {
        String order = placeDineIn("A5", "Adana Kebap", 2);
        long id = id(order);
        long line = lineId(order);

        // a manager comps one portion on their own authority
        mvc.perform(json(as(ADMIN, post("/api/v1/orders/{id}/lines/{line}/adjust", id, line)), """
                        {"kind": "COMP", "quantity": 1, "reason": "Long wait"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].compedQuantity").value(1))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.pricing.subtotal").value(520.00))
                .andExpect(jsonPath("$.adjustments[0].approvedBy").value("admin"));

        mvc.perform(json(as(WAITER, put("/api/v1/orders/{id}/discount", id)), """
                        {"percent": 10, "reason": "Regular guest"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
        mvc.perform(json(as(WAITER, put("/api/v1/orders/{id}/discount", id)), """
                        {"percent": 10, "amount": 5, "reason": "x", "approval": {"manager": "admin", "pin": "%s"}}"""
                        .formatted(MANAGER_PIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISCOUNT_INVALID"));
        mvc.perform(json(as(WAITER, put("/api/v1/orders/{id}/discount", id)), """
                        {"percent": 10, "reason": "Regular guest", "approval": {"manager": "admin", "pin": "%s"}}"""
                        .formatted(MANAGER_PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing.discount").value(52.00))
                .andExpect(jsonPath("$.pricing.tax").value(46.80))
                .andExpect(jsonPath("$.pricing.total").value(514.80))
                .andExpect(jsonPath("$.manualDiscount.percent").value(10));
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billIdOf(id))))
                .andExpect(jsonPath("$.total").value(514.80));

        // taking the discount away again
        mvc.perform(json(as(ADMIN, put("/api/v1/orders/{id}/discount", id)), """
                        {"reason": "Applied by mistake"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing.discount").value(0.00))
                .andExpect(jsonPath("$.manualDiscount").doesNotExist());
    }

    @Test
    void aPaidBillCannotBeMadeCheaperWithoutARefund() throws Exception {
        String order = placeDineIn("A6", "Demlik Çay", 2); // 99.00
        long id = id(order);
        long bill = billIdOf(id);
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", bill)), """
                        {"method": "CARD", "amount": 99.00}"""))
                .andExpect(status().isCreated());
        mvc.perform(json(as(ADMIN, post("/api/v1/orders/{id}/lines/{line}/adjust", id, lineId(order))), """
                        {"kind": "COMP", "quantity": 1, "reason": "Cold"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_BELOW_PAID"));
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", id)))
                .andExpect(jsonPath("$.lines[0].compedQuantity").value(0))
                .andExpect(jsonPath("$.adjustments").isEmpty());
    }

    @Test
    void aSeatingMovesToAFreeTableAndMergesIntoAnotherBill() throws Exception {
        long first = id(placeDineIn("T1", "Lahmacun", 1));
        long second = id(placeDineIn("T1", "Demlik Çay", 2));
        long other = id(placeDineIn("T2", "Adana Kebap", 1));
        long bill = billIdOf(first);

        mvc.perform(json(as(WAITER, post("/api/v1/tables/{id}/move", tableId("T1"))), """
                        {"tableId": %d}""".formatted(tableId("T2"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));
        mvc.perform(json(as(WAITER, post("/api/v1/tables/{id}/move", tableId("T1"))), """
                        {"tableId": %d}""".formatted(tableId("T3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bill))
                .andExpect(jsonPath("$.table.code").value("T3"));
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", second)))
                .andExpect(jsonPath("$.table.code").value("T3"))
                .andExpect(jsonPath("$.adjustments[0].kind").value("MOVE"))
                .andExpect(jsonPath("$.adjustments[0].detail").value("T1 → T3"));

        // T2 joins T3's bill: one bill, three orders; T2's own bill is void
        long otherBill = billIdOf(other);
        String merged = mvc.perform(json(as(WAITER, post("/api/v1/tables/{id}/merge", tableId("T2"))), """
                        {"tableId": %d}""".formatted(tableId("T3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bill))
                .andExpect(jsonPath("$.orders.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(merged, "$.total")).doubleValue()).isEqualTo(242.0 + 99.0 + 572.0);
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", otherBill)))
                .andExpect(jsonPath("$.status").value("VOID"));
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", other)))
                .andExpect(jsonPath("$.table.code").value("T3"));

        // nobody left at T1 or T2
        mvc.perform(json(as(WAITER, post("/api/v1/tables/{id}/move", tableId("T1"))), """
                        {"tableId": %d}""".formatted(tableId("T2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_SEATED"));
        mvc.perform(json(as(CHEF, post("/api/v1/tables/{id}/move", tableId("T3"))), """
                        {"tableId": %d}""".formatted(tableId("T1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBillThatTookMoneyIsNotMerged() throws Exception {
        long paidOrder = id(placeDineIn("W2", "Demlik Çay", 1));
        id(placeDineIn("A3", "Demlik Çay", 1));
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billIdOf(paidOrder))), """
                        {"method": "CASH", "amount": 20, "tendered": 20}"""))
                .andExpect(status().isCreated());
        mvc.perform(json(as(WAITER, post("/api/v1/tables/{id}/merge", tableId("W2"))), """
                        {"tableId": %d}""".formatted(tableId("A3"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MERGE_SOURCE_HAS_PAYMENTS"));
    }

    private String placeDineIn(String table, String item, int quantity) throws Exception {
        return mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "tableId": %d, "customerName": "Table %s", "paymentMethod": "CARD",
                         "items": [{"menuItemId": %d, "quantity": %d}]}""".formatted(tableId(table), table, menuItemId(item), quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static long id(String order) {
        return ((Number) JsonPath.read(order, "$.id")).longValue();
    }

    private static long lineId(String order) {
        return ((Number) JsonPath.read(order, "$.lines[0].id")).longValue();
    }
}
