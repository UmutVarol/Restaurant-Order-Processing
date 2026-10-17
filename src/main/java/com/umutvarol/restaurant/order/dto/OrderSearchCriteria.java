package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import java.time.LocalDate;
import java.util.List;

/**
 * Filters for the order list. All fields are optional; {@code from} and {@code to} are inclusive calendar
 * days in the restaurant's time zone.
 */
public record OrderSearchCriteria(
        List<OrderStatus> statuses,
        OrderType type,
        String query,
        LocalDate from,
        LocalDate to) {
}
