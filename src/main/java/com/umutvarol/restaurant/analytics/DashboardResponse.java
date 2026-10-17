package com.umutvarol.restaurant.analytics;

import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the manager dashboard shows for a period of {@code days} days ending today, plus the KPIs of
 * the preceding period of equal length for trend comparison.
 */
public record DashboardResponse(
        LocalDate from,
        LocalDate to,
        int days,
        Kpis current,
        Kpis previous,
        long activeOrders,
        List<DailyPoint> daily,
        List<HourlyPoint> hourly,
        List<CategorySales> categories,
        List<ItemSales> topItems,
        List<StatusCount> statuses,
        List<TypeBreakdown> orderTypes,
        List<PaymentBreakdown> payments) {

    /**
     * Headline numbers. Revenue and order counts exclude cancelled orders.
     *
     * @param averageKitchenMinutes mean time from order placement to READY, or {@code null} if unknown
     */
    public record Kpis(BigDecimal revenue, long orders, BigDecimal averageOrderValue, long cancelledOrders,
                       long itemsSold, Double averageKitchenMinutes) {
    }

    public record DailyPoint(LocalDate date, BigDecimal revenue, long orders) {
    }

    public record HourlyPoint(int hour, long orders, BigDecimal revenue) {
    }

    public record StatusCount(OrderStatus status, long count) {
    }

    public record TypeBreakdown(OrderType type, long orders, BigDecimal revenue) {
    }

    public record PaymentBreakdown(PaymentMethod method, long orders, BigDecimal revenue) {
    }
}
