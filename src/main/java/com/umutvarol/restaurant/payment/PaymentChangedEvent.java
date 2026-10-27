package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.order.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Published when money moves on a bill or the bill closes, so open screens (the bill, the floor plan, the
 * cashier) refresh. Like order events, it reaches listeners only after the transaction commits.
 */
public record PaymentChangedEvent(
        Kind kind,
        Long billId,
        BillStatus billStatus,
        BigDecimal balance,
        @Nullable Long paymentId,
        @Nullable PaymentMethod method,
        @Nullable PaymentStatus paymentStatus,
        @Nullable BigDecimal amount,
        @Nullable String tableCode,
        Instant occurredAt) {

    public enum Kind {
        PAYMENT,
        REFUND,
        BILL
    }

    static PaymentChangedEvent payment(Payment payment, Instant at) {
        Bill bill = payment.getBill();
        return new PaymentChangedEvent(Kind.PAYMENT, bill.getId(), bill.getStatus(), bill.balance(), payment.getId(),
                payment.getMethod(), payment.getStatus(), payment.getAmount(), tableCode(bill), at);
    }

    static PaymentChangedEvent refund(Refund refund, Instant at) {
        Payment payment = refund.getPayment();
        Bill bill = payment.getBill();
        return new PaymentChangedEvent(Kind.REFUND, bill.getId(), bill.getStatus(), bill.balance(), payment.getId(),
                payment.getMethod(), payment.getStatus(), refund.getAmount(), tableCode(bill), at);
    }

    static PaymentChangedEvent bill(Bill bill, Instant at) {
        return new PaymentChangedEvent(Kind.BILL, bill.getId(), bill.getStatus(), bill.balance(), null, null, null,
                null, tableCode(bill), at);
    }

    private static @Nullable String tableCode(Bill bill) {
        return bill.getTable() == null ? null : bill.getTable().getCode();
    }
}
