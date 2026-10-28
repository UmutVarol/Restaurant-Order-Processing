package com.umutvarol.restaurant.fiscal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every paid bill gets one legal document: an ÖKC receipt, or an e-Arşiv invoice when the guest asks for one. */
class FiscalApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void aPaidBillGetsItsReceiptOnce() throws Exception {
        long order = placeTakeaway("Fiscal Receipt", "Lahmacun", 2);
        long bill = payInFull(order, "CASH");
        complete(order);

        mvc.perform(as(WAITER, get("/api/v1/fiscal/bills/{id}", bill)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RECEIPT"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.documentNumber").isNotEmpty())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.total").value(484.0));
        // a bill gets one legal document only
        mvc.perform(json(as(WAITER, post("/api/v1/fiscal/bills/{id}/invoice", bill)), """
                        {"name": "Too Late Ltd", "taxId": "1234567890"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FISCAL_ALREADY_ISSUED"));
        mvc.perform(as(ADMIN, get("/api/v1/fiscal/z-report")))
                .andExpect(jsonPath("$.receipts").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.difference").value(0.0));
    }

    @Test
    void aGuestWhoAsksForAnInvoiceGetsAnEArsivInvoiceWhenTheBillIsPaid() throws Exception {
        long order = placeTakeaway("Fiscal Invoice", "Adana Kebap", 1);
        long bill = billIdOf(order);
        mvc.perform(json(as(WAITER, post("/api/v1/fiscal/bills/{id}/invoice", bill)), """
                        {"name": "Sofra Gıda A.Ş.", "taxId": "1234567891"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TAX_ID_INVALID"));
        mvc.perform(json(as(WAITER, post("/api/v1/fiscal/bills/{id}/invoice", bill)), """
                        {"name": "Sofra Gıda A.Ş.", "taxId": "1234567890", "address": "Moda, İstanbul"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.kind").value("E_ARSIV"))
                .andExpect(jsonPath("$.buyerTaxId").value("•••••••890"));

        payInFull(order, "CARD");
        complete(order);
        mvc.perform(as(WAITER, get("/api/v1/fiscal/bills/{id}", bill)))
                .andExpect(jsonPath("$.kind").value("E_ARSIV"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.buyerName").value("Sofra Gıda A.Ş."));
        mvc.perform(as(WAITER, get("/api/v1/fiscal/documents")))
                .andExpect(jsonPath("$[?(@.billId == %d)].buyerTaxId".formatted(bill)).value("•••••••890"));
    }

    @Test
    void onlyTillStaffSeeDocumentsAndOnlyManagersTheZReport() throws Exception {
        mvc.perform(as(CHEF, get("/api/v1/fiscal/documents"))).andExpect(status().isForbidden());
        mvc.perform(as(WAITER, get("/api/v1/fiscal/z-report"))).andExpect(status().isForbidden());
        mvc.perform(as(WAITER, get("/api/v1/fiscal/bills/{id}", 999_999))).andExpect(status().isNotFound());
    }

    private void complete(long orderId) throws Exception {
        for (String next : List.of("PREPARING", "READY", "COMPLETED")) {
            mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", orderId)))
                    .andExpect(jsonPath("$.status").value(next));
        }
    }
}
