package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.payment.Bill;
import com.umutvarol.restaurant.payment.BillStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A bill with its orders and payments. {@code balance} is what is still due, {@code available} what may still be
 * charged while online payments are in progress.
 */
public record BillResponse(
        Long id,
        BillStatus status,
        TableRef table,
        List<BillOrder> orders,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal pending,
        BigDecimal balance,
        BigDecimal available,
        BigDecimal tips,
        BigDecimal refunded,
        boolean settled,
        PaymentMethod preferredMethod,
        Instant openedAt,
        Instant closedAt,
        List<PaymentResponse> payments) {

    public record TableRef(Long id, String code) {
    }

    /** Lines are listed so the guests can split by what each of them had. */
    public record BillOrder(Long id, OrderType type, OrderStatus status, String customerName, BigDecimal subtotal,
                            BigDecimal total, List<Line> lines) {
    }

    public record Line(String name, String icon, @Nullable String options, int quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal) {
    }

    public static BillResponse from(Bill bill) {
        List<Order> orders = bill.getOrders();
        return new BillResponse(
                bill.getId(),
                bill.getStatus(),
                bill.getTable() == null ? null : new TableRef(bill.getTable().getId(), bill.getTable().getCode()),
                orders.stream().map(BillResponse::order).toList(),
                bill.total(),
                bill.paid(),
                bill.pending(),
                bill.balance(),
                bill.available(),
                bill.tips(),
                bill.refunded(),
                bill.isSettled(),
                orders.isEmpty() ? null : orders.getLast().getPaymentMethod(),
                bill.getOpenedAt(),
                bill.getClosedAt(),
                bill.getPayments().stream().map(PaymentResponse::from).toList());
    }

    private static BillOrder order(Order order) {
        return new BillOrder(order.getId(), order.getType(), order.getStatus(), order.getCustomerName(),
                order.getPricing().subtotal(), order.getPricing().total(),
                order.getLines().stream()
                        .map(line -> new Line(line.getItemName(), line.getItemIcon(), line.optionSummary(), line.activeQuantity(),
                                line.getUnitPrice(), line.getLineTotal()))
                        .toList());
    }
}
