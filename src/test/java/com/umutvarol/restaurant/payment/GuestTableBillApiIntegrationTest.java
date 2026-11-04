package com.umutvarol.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Guests pay their table's bill from the table's QR code, without waiting for a waiter to bring a payment link, and
 * give their invoice details on their phone.
 */
class GuestTableBillApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void guestsOpenTheirTablesBillFromItsCodeGiveInvoiceDetailsAndPay() throws Exception {
        String table = newTable();
        String code = issueQr(table);
        // nobody is seated yet: nothing to pay
        mvc.perform(get("/api/v1/public/table/{token}/bill", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.reason").value("NOTHING_DUE"));
        mvc.perform(post("/api/v1/public/table/{token}/bill", code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_NOTHING_DUE"));

        long orderId = placeDineIn(table, "Demlik Çay", 2);   // 99.00
        long billId = billIdOf(orderId);
        serve(orderId);
        mvc.perform(get("/api/v1/public/table/{token}/bill", code))
                .andExpect(jsonPath("$.payable").value(true))
                .andExpect(jsonPath("$.due").value(99.00));

        String opened = mvc.perform(post("/api/v1/public/table/{token}/bill", code))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(opened, "$.token");
        mvc.perform(get("/api/v1/public/pay/{token}", link))
                .andExpect(jsonPath("$.tableCode").value(table))
                .andExpect(jsonPath("$.due").value(99.00))
                .andExpect(jsonPath("$.share").doesNotExist())
                .andExpect(jsonPath("$.payable").value(true));

        // invoice details from the phone: checked like at the till, never shown back, and given once
        mvc.perform(get("/api/v1/public/pay/{token}/invoice", link))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.name").doesNotExist());
        mvc.perform(json(post("/api/v1/public/pay/{token}/invoice", link), """
                        {"name": "Moda Yazılım Ltd.", "taxId": "1234567891"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TAX_ID_INVALID"));
        String invoice = mvc.perform(json(post("/api/v1/public/pay/{token}/invoice", link), """
                        {"name": "Moda Yazılım Ltd.", "taxId": "1234567890", "address": "Moda, İstanbul"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Moda Yazılım Ltd."))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(invoice).doesNotContain("1234567890").doesNotContain("890");
        mvc.perform(json(post("/api/v1/public/pay/{token}/invoice", link), """
                        {"name": "Someone Else", "taxId": "1234567890"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_REQUESTED"));

        // the guest pays everything, and the invoice is issued with it
        String checkout = mvc.perform(json(post("/api/v1/public/pay/{token}/checkout", link), """
                        {"amount": 99, "tip": 10}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String providerToken = ((String) JsonPath.read(checkout, "$.paymentPageUrl")).substring("/#/sim-checkout/".length());
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/api/v1/public/table/{token}/bill", code))
                .andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.reason").value("NOTHING_DUE"));

        // paid in full and served: the table's order is done without a waiter closing it
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", orderId)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(as(WAITER, get("/api/v1/fiscal/bills/{id}", billId)))
                .andExpect(jsonPath("$.kind").value("E_ARSIV"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.buyerName").value("Moda Yazılım Ltd."));
        // a paid bill's link still says whose invoice it is, but takes no new details
        mvc.perform(get("/api/v1/public/pay/{token}/invoice", link))
                .andExpect(jsonPath("$.name").value("Moda Yazılım Ltd."))
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void aTableOpensOnlyAHandfulOfLinksPerBill() throws Exception {
        String table = newTable();
        String code = issueQr(table);
        long orderId = placeDineIn(table, "Ayran", 1);
        for (int i = 0; i < OnlinePaymentService.TABLE_LINKS_PER_BILL; i++) {
            mvc.perform(post("/api/v1/public/table/{token}/bill", code)).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/public/table/{token}/bill", code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_LINKS_EXHAUSTED"));
        // a waiter can still bring a link
        mvc.perform(as(WAITER, post("/api/v1/bills/{id}/links", billIdOf(orderId)))).andExpect(status().isCreated());
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", orderId)), """
                        {"reason": "Test finished"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void aMadeUpCodeOrLinkOpensNothing() throws Exception {
        mvc.perform(get("/api/v1/public/table/{token}/bill", "x".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/table/{token}/bill", "x".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/pay/{token}/invoice", "y".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/public/pay/{token}/invoice", "y".repeat(43)), """
                        {"name": "Nobody", "taxId": "1234567890"}"""))
                .andExpect(status().isNotFound());
    }

    private String issueQr(String table) throws Exception {
        String body = mvc.perform(as(ADMIN, post("/api/v1/tables/{id}/qr", tableId(table))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private long placeDineIn(String table, String item, int quantity) throws Exception {
        String body = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "tableId": %d, "customerName": "Guests", "paymentMethod": "ONLINE",
                         "items": [{"menuItemId": %d, "quantity": %d}]}""".formatted(tableId(table), menuItemId(item), quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void serve(long orderId) throws Exception {
        for (int step = 0; step < 3; step++) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", orderId))).andExpect(status().isOk());
        }
    }
}
