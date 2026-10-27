package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Takings for one business day, per payment method. {@code expectedCash} is what the drawer should hold from the
 * day's sales: cash payments and cash tips, minus cash handed back as refunds.
 */
public record DaySummary(
        LocalDate date,
        List<MethodTotal> methods,
        BigDecimal sales,
        BigDecimal tips,
        BigDecimal refunds,
        BigDecimal expectedCash,
        int payments) {

    public record MethodTotal(PaymentMethod method, int count, BigDecimal amount, BigDecimal tips, BigDecimal refunds) {

        public BigDecimal net() {
            return amount.subtract(refunds);
        }
    }

    BigDecimal totalFor(PaymentMethod method) {
        return methods.stream().filter(total -> total.method() == method).map(MethodTotal::net)
                .findFirst().orElse(Money.ZERO);
    }
}
