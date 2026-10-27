package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.payment.Bill;
import com.umutvarol.restaurant.payment.BillStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One line of the cashier's list of open bills. */
public record BillSummaryResponse(Long id, BillStatus status, String tableCode, String customerName,
                                  List<Long> orderIds, BigDecimal total, BigDecimal paid, BigDecimal balance,
                                  BigDecimal pending, boolean hasActiveOrders, Instant openedAt) {

    public static BillSummaryResponse from(Bill bill) {
        return new BillSummaryResponse(
                bill.getId(),
                bill.getStatus(),
                bill.getTable() == null ? null : bill.getTable().getCode(),
                bill.getOrders().isEmpty() ? null : bill.getOrders().getFirst().getCustomerName(),
                bill.getOrders().stream().map(order -> order.getId()).toList(),
                bill.total(),
                bill.paid(),
                bill.balance(),
                bill.pending(),
                bill.hasActiveOrders(),
                bill.getOpenedAt());
    }
}
