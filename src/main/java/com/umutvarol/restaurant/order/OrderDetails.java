package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.table.DiningTable;
import org.jspecify.annotations.Nullable;

/** Who an order is for and how it should be fulfilled and paid. */
public record OrderDetails(
        OrderType type,
        String customerName,
        @Nullable String customerPhone,
        @Nullable DiningTable table,
        @Nullable String deliveryAddress,
        @Nullable String note,
        PaymentMethod paymentMethod,
        @Nullable String couponCode) {
}
