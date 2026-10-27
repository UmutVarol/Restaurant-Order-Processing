package com.umutvarol.restaurant.payment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.assertj.core.api.Assertions;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class PaymentApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OnlinePaymentService onlinePaymentService;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private BillRepository bills;
    @Autowired
    private RefundRepository refunds;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void aTablesRoundsShareOneBillThatCanBeSplitAndClosesWhenPaid() throws Exception {
        long first = placeDineIn("A1", "Demlik Çay", 2);   // 2 × 45 = 90 + 10 % tax = 99
        long second = placeDineIn("A1", "Demlik Çay", 1);  // 49.50
        long billId = billIdOf(first);
        Assertions.assertThat(billIdOf(second)).isEqualTo(billId);
        serve(first);
        serve(second);

        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.table.code").value("A1"))
                .andExpect(jsonPath("$.orders", hasSize(2)))
                .andExpect(jsonPath("$.total").value(148.50))
                .andExpect(jsonPath("$.balance").value(148.50));

        // First guest pays by card with a tip; the till asks for the terminal's approval code.
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "CARD", "amount": 74.25, "tip": 10, "approvalCode": "A1B2C3"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.approvalCode").value("A1B2C3"))
                .andExpect(jsonPath("$.bill.balance").value(74.25))
                .andExpect(jsonPath("$.bill.status").value("OPEN"));
        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", first)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BILL_NOT_PAID"));

        // Second guest pays cash and gets change; the bill is settled, the orders complete, the table frees up.
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "CASH", "amount": 74.25, "tendered": 100}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.changeDue").value(25.75))
                .andExpect(jsonPath("$.bill.balance").value(0.00))
                .andExpect(jsonPath("$.bill.tips").value(10.00))
                .andExpect(jsonPath("$.bill.status").value("CLOSED"))
                .andExpect(jsonPath("$.bill.orders[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.bill.orders[1].status").value("COMPLETED"));

        // The next guests at the table start a new bill.
        long next = placeDineIn("A1", "Demlik Çay", 1);
        Assertions.assertThat(billIdOf(next)).isNotEqualTo(billId);
    }

    @Test
    void refusesToTakeMoreThanIsDue() throws Exception {
        long billId = billIdOf(placeTakeaway("Ece", "Demlik Çay", 1));

        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "CARD", "amount": 1000}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_BALANCE"))
                .andExpect(jsonPath("$.parameters.available").value(49.50));
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "CASH", "amount": 49.50, "tendered": 40}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_NOT_ENOUGH"));
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "ONLINE", "amount": 49.50}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRetriedPaymentWithTheSameIdempotencyKeyIsTakenOnce() throws Exception {
        long billId = billIdOf(placeTakeaway("Mert", "Demlik Çay", 2));
        String key = UUID.randomUUID().toString();
        String body = """
                {"method": "CARD", "amount": 50}""";

        String first = mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId))
                        .header("Idempotency-Key", key), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)).header("Idempotency-Key", key), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.id").value((Integer) JsonPath.read(first, "$.payment.id")))
                .andExpect(jsonPath("$.bill.paid").value(50.00))
                .andExpect(jsonPath("$.bill.payments", hasSize(1)));

        long otherBill = billIdOf(placeTakeaway("Mert", "Demlik Çay", 2));
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", otherBill)).header("Idempotency-Key", key), body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void cancellingAPaidOrderRefundsIt() throws Exception {
        long orderId = placeTakeaway("Deniz", "Demlik Çay", 1);
        long billId = payInFull(orderId, "CASH");

        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", orderId)), """
                        {"reason": "Guest left"}"""))
                .andExpect(status().isOk());

        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.status").value("VOID"))
                .andExpect(jsonPath("$.refunded").value(49.50))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.payments[0].refunds[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.payments[0].refunds[0].reason", containsString("cancelled")));
    }

    @Test
    void onlyManagersRefundAndOnlyStaffSeeBills() throws Exception {
        long orderId = placeTakeaway("Onur", "Demlik Çay", 2);
        long billId = payInFull(orderId, "CARD");
        long paymentId = ((Number) JsonPath.read(mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andReturn().getResponse().getContentAsString(), "$.payments[0].id")).longValue();

        mvc.perform(as(CHEF, get("/api/v1/bills/{id}", billId))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bills/{id}", billId)).andExpect(status().isUnauthorized());
        mvc.perform(json(as(WAITER, post("/api/v1/payments/{id}/refunds", paymentId)), """
                        {"amount": 10, "reason": "Cold tea"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(json(as(ADMIN, post("/api/v1/payments/{id}/refunds", paymentId)), """
                        {"amount": 10, "reason": "Cold tea"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunded").value(10.00))
                .andExpect(jsonPath("$.refundable").value(89.00));
        mvc.perform(json(as(ADMIN, post("/api/v1/payments/{id}/refunds", paymentId)), """
                        {"amount": 90, "reason": "Too much"}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_PAYMENT"));
    }

    @Test
    void theDayReportCountsTakingsAndTheDayClosesOnce() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"));
        String before = mvc.perform(as(ADMIN, get("/api/v1/payments/day").param("date", today.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        double cashBefore = ((Number) JsonPath.read(before, "$.summary.expectedCash")).doubleValue();

        long billId = billIdOf(placeTakeaway("Sena", "Demlik Çay", 2));
        mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/payments", billId)), """
                        {"method": "CASH", "amount": 99, "tip": 5, "tendered": 110}"""))
                .andExpect(status().isCreated());

        mvc.perform(as(WAITER, get("/api/v1/payments/day"))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, get("/api/v1/payments/day")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.methods[0].method").value("CASH"))
                .andExpect(jsonPath("$.summary.expectedCash").value(cashBefore + 104))
                .andExpect(jsonPath("$.closing").doesNotExist());

        mvc.perform(json(as(ADMIN, post("/api/v1/payments/day/close")), """
                        {"date": "%s", "countedCash": %s, "note": "Short by one lira"}""".formatted(today, cashBefore + 103)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closing.variance").value(-1.00))
                .andExpect(jsonPath("$.closing.closedBy").value(ADMIN));
        mvc.perform(json(as(ADMIN, post("/api/v1/payments/day/close")), """
                        {"date": "%s", "countedCash": 0}""".formatted(today)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_ALREADY_CLOSED"));
        mvc.perform(json(as(ADMIN, post("/api/v1/payments/day/close")), """
                        {"date": "%s", "countedCash": 0}""".formatted(today.plusDays(2))))
                .andExpect(jsonPath("$.code").value("DAY_NOT_OVER"));
    }

    // ------------------------------------------------------------------------------------------- QR payments

    @Test
    void aGuestPaysTheirShareFromTheirPhone() throws Exception {
        long orderId = placeDineIn("A2", "Demlik Çay", 2);   // 99.00
        long billId = billIdOf(orderId);
        serve(orderId);

        String link = mvc.perform(json(as(WAITER, post("/api/v1/bills/{id}/links", billId)), """
                        {"amount": 60}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", containsString("/#/pay/")))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(link, "$.token");

        // No sign-in: the link is the permission. Only what the guest needs is shown.
        mvc.perform(get("/api/v1/public/pay/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCode").value("A2"))
                .andExpect(jsonPath("$.due").value(99.00))
                .andExpect(jsonPath("$.share").value(60.00))
                .andExpect(jsonPath("$.payable").value(true))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.customerName").doesNotExist());

        String checkout = mvc.perform(json(post("/api/v1/public/pay/{token}/checkout", token), """
                        {"amount": 99, "tip": 6, "name": "Elif Doğan"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentPageUrl", startsWith("/#/sim-checkout/sim_")))
                .andReturn().getResponse().getContentAsString();
        String providerToken = ((String) JsonPath.read(checkout, "$.paymentPageUrl")).substring("/#/sim-checkout/".length());

        // While the guest is on the provider's page the share is held, so the till cannot take it twice.
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.pending").value(60.00))
                .andExpect(jsonPath("$.available").value(39.00));

        String page = mvc.perform(get("/api/v1/public/sim-checkout/{token}", providerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(66.00))
                .andReturn().getResponse().getContentAsString();
        String callbackUrl = JsonPath.read(page, "$.callbackUrl");
        // The link token never travels through the provider.
        Assertions.assertThat(callbackUrl).endsWith("/api/v1/payments/callback/simulated")
                .doesNotContain(token);

        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "/#/pay/return"));

        mvc.perform(get("/api/v1/public/pay/{token}", token))
                .andExpect(jsonPath("$.lastPayment.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.lastPayment.tip").value(6.00))
                .andExpect(jsonPath("$.due").value(39.00))
                .andExpect(jsonPath("$.share").value(39.00));

        // The callback can be replayed without effect.
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "/#/pay/return"));
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.paid").value(60.00))
                .andExpect(jsonPath("$.payments[0].method").value("ONLINE"))
                .andExpect(jsonPath("$.payments[0].viaLink").value(true));
    }

    @Test
    void aDeclinedCardLeavesTheBillDue() throws Exception {
        long billId = billIdOf(placeTakeaway("Can", "Demlik Çay", 1));
        String token = createLink(billId);
        String providerToken = startCheckout(token);

        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": false}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isSeeOther());

        mvc.perform(get("/api/v1/public/pay/{token}", token))
                .andExpect(jsonPath("$.lastPayment.status").value("FAILED"))
                .andExpect(jsonPath("$.lastPayment.failureReason", containsString("declined")))
                .andExpect(jsonPath("$.due").value(49.50))
                .andExpect(jsonPath("$.payable").value(true));
    }

    @Test
    void anAbandonedCheckoutExpiresAndFreesTheAmount() throws Exception {
        long billId = billIdOf(placeTakeaway("Gizem", "Demlik Çay", 1));
        String providerToken = startCheckout(createLink(billId));
        Long paymentId = payments.findByProviderToken(providerToken).orElseThrow().getId();

        onlinePaymentService.reconcile(paymentId, true);

        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.payments[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.pending").value(0.00))
                .andExpect(jsonPath("$.available").value(49.50));
    }

    @Test
    void cancellingAnOrderPaidOnlineRefundsItThroughTheProvider() throws Exception {
        long orderId = placeTakeaway("Kerem", "Demlik Çay", 1);
        long billId = billIdOf(orderId);
        String token = createLink(billId);
        String providerToken = startCheckout(token);
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""));
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED));

        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", orderId)), """
                        {"reason": "Out of stock"}"""))
                .andExpect(status().isOk());

        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.payments[0].refunds[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refunded").value(49.50));
        mvc.perform(get("/api/v1/public/pay/{token}", token))
                .andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.reason").value("PAID"));
    }

    @Test
    void unknownLinksAndCheckoutsRevealNothing() throws Exception {
        mvc.perform(get("/api/v1/public/pay/{token}", "x".repeat(43)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_LINK_NOT_FOUND"));
        mvc.perform(get("/api/v1/public/pay/{token}", "short"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/sim-checkout/{token}", "sim_unknown"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", "forged")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "/#/pay/return"));
        // A forged provider notification only triggers a lookup, and answers the same either way.
        mvc.perform(json(post("/api/v1/payments/webhook/simulated"), """
                        {"token": "forged", "status": "SUCCESS", "paymentConversationId": "sofra-p999999"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void aLinkHoldsOneCheckoutAtATime() throws Exception {
        long billId = billIdOf(placeTakeaway("Melis", "Demlik Çay", 2));
        String token = createLink(billId);
        startCheckout(token);

        // A second attempt straight away is refused, so a script cannot tie up the bill with holds.
        mvc.perform(json(post("/api/v1/public/pay/{token}/checkout", token), "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_IN_PROGRESS"));
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.pending").value(99.00));
    }

    @Test
    void staffCanReleaseAnAbandonedCheckout() throws Exception {
        long billId = billIdOf(placeTakeaway("Hakan", "Demlik Çay", 1));
        startCheckout(createLink(billId));
        long paymentId = ((Number) JsonPath.read(mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andReturn().getResponse().getContentAsString(), "$.payments[0].id")).longValue();

        mvc.perform(as(CHEF, post("/api/v1/bills/{id}/payments/{paymentId}/release", billId, paymentId)))
                .andExpect(status().isForbidden());
        mvc.perform(as(WAITER, post("/api/v1/bills/{id}/payments/{paymentId}/release", billId + 1, paymentId)))
                .andExpect(status().isNotFound());
        mvc.perform(as(WAITER, post("/api/v1/bills/{id}/payments/{paymentId}/release", billId, paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.payments[0].failureReason").value("Released by staff."))
                .andExpect(jsonPath("$.available").value(49.50));
    }

    @Test
    void aPaymentReleasedByMistakeIsStillRecordedWhenTheProviderSaysItWentThrough() throws Exception {
        long billId = billIdOf(placeTakeaway("Tolga", "Demlik Çay", 1));
        String providerToken = startCheckout(createLink(billId));
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""));
        long paymentId = payments.findByProviderToken(providerToken).orElseThrow().getId();

        // The provider is asked before releasing: the guest had paid, so nothing is released.
        mvc.perform(as(WAITER, post("/api/v1/bills/{id}/payments/{paymentId}/release", billId, paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.balance").value(0.00));
    }

    @Test
    void aProviderNotificationRecordsAPaymentWhoseGuestNeverCameBack() throws Exception {
        long billId = billIdOf(placeTakeaway("Pınar", "Demlik Çay", 1));
        String providerToken = startCheckout(createLink(billId));
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""));
        long paymentId = payments.findByProviderToken(providerToken).orElseThrow().getId();

        mvc.perform(json(post("/api/v1/payments/webhook/simulated"), """
                        {"paymentConversationId": "sofra-p%d", "status": "FAILURE"}""".formatted(paymentId)))
                .andExpect(status().isOk());

        // What the notification claimed (FAILURE) is ignored: the provider itself says the payment succeeded.
        mvc.perform(as(WAITER, get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"));
    }

    @Test
    void aRefundIsClaimedBeforeItIsSentSoItIsNeverSentTwice() throws Exception {
        long billId = billIdOf(placeTakeaway("Buse", "Demlik Çay", 1));
        String providerToken = startCheckout(createLink(billId));
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""));
        mvc.perform(post("/api/v1/payments/callback/simulated").param("token", providerToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED));
        // A pending provider refund, recorded without being sent yet.
        Long refundId = transactions.execute(status -> {
            Bill bill = bills.findForUpdate(billId).orElseThrow();
            Payment payment = bill.getPayments().getFirst();
            Refund refund = bill.refund(payment, Money.of("10.00"), "Test", java.time.Instant.now(), ADMIN);
            bills.flush();
            return refund.getId();
        });
        java.time.Instant now = java.time.Instant.now();

        Integer first = transactions.execute(status -> refunds.claim(refundId, now, now.plusSeconds(120)));
        Integer second = transactions.execute(status -> refunds.claim(refundId, now, now.plusSeconds(120)));
        Assertions.assertThat(first).isEqualTo(1);
        Assertions.assertThat(second).isEqualTo(0);
        // While claimed, another attempt does not send it.
        onlinePaymentService.sendRefund(refundId);
        Assertions.assertThat(refunds.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING);
        // A claim that ran out (the sender crashed) can be taken over, and the refund then goes through once.
        Integer takeover = transactions.execute(status -> refunds.claim(refundId, now.plusSeconds(180), now.plusSeconds(181)));
        Assertions.assertThat(takeover).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private long placeDineIn(String table, String item, int quantity) throws Exception {
        String body = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "tableId": %d, "customerName": "Guests", "paymentMethod": "CARD",
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

    private String createLink(long billId) throws Exception {
        String link = mvc.perform(as(WAITER, post("/api/v1/bills/{id}/links", billId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(link, "$.token");
    }

    private String startCheckout(String token) throws Exception {
        String checkout = mvc.perform(json(post("/api/v1/public/pay/{token}/checkout", token), "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((String) JsonPath.read(checkout, "$.paymentPageUrl")).substring("/#/sim-checkout/".length());
    }
}
