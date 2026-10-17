package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderStatusChange;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderType type,
        OrderStatus status,
        OrderStatus nextStatus,
        boolean cancellable,
        String customerName,
        String customerPhone,
        TableRef table,
        String deliveryAddress,
        String note,
        PaymentMethod paymentMethod,
        String couponCode,
        List<OrderLineResponse> lines,
        int itemCount,
        PriceBreakdown pricing,
        Instant createdAt,
        Instant updatedAt,
        Instant statusSince,
        String createdBy,
        String cancellationReason,
        List<StatusChange> history) {

    public record TableRef(Long id, String code) {
    }

    public record StatusChange(OrderStatus status, Instant at, String by) {

        static StatusChange from(OrderStatusChange change) {
            return new StatusChange(change.getStatus(), change.getChangedAt(), change.getChangedBy());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getType(),
                order.getStatus(),
                order.nextStatus().orElse(null),
                order.getStatus().isCancellable(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                order.getTable() == null ? null : new TableRef(order.getTable().getId(), order.getTable().getCode()),
                order.getDeliveryAddress(),
                order.getNote(),
                order.getPaymentMethod(),
                order.getCouponCode(),
                order.getLines().stream().map(OrderLineResponse::from).toList(),
                order.itemCount(),
                order.getPricing(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.statusSince(),
                order.getCreatedBy(),
                order.getCancellationReason(),
                order.getHistory().stream().map(StatusChange::from).toList());
    }
}
