package com.umutvarol.restaurant.analytics;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The branches side by side for a period (Chain plan): what each sold, against the period before.
 *
 * @param averageKitchenMinutes from order to ready, or null when no order got there
 */
public record BranchReport(int days, List<Row> branches) {

    public record Row(Long branchId, String name, BigDecimal revenue, BigDecimal previousRevenue, long orders,
                      BigDecimal averageOrderValue, long cancelledOrders, @Nullable Double averageKitchenMinutes) {
    }
}
