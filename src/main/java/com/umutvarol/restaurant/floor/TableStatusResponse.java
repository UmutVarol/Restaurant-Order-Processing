package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.table.TableZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A table together with the orders currently open on it. */
public record TableStatusResponse(
        Long id,
        String code,
        int seats,
        TableZone zone,
        boolean occupied,
        Instant occupiedSince,
        BigDecimal openTotal,
        List<OpenOrder> openOrders,
        Instant qrIssuedAt) {

    public record OpenOrder(Long id, OrderStatus status, String customerName, int itemCount, BigDecimal total,
                            Instant createdAt) {
    }
}
