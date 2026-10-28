package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.AdjustmentKind;
import com.umutvarol.restaurant.order.OrderAdjustment;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record AdjustmentResponse(AdjustmentKind kind, @Nullable String itemName, @Nullable Integer quantity,
                                 @Nullable BigDecimal amount, @Nullable String detail, String by,
                                 @Nullable String approvedBy, Instant at) {

    static AdjustmentResponse from(OrderAdjustment adjustment) {
        return new AdjustmentResponse(adjustment.getKind(), adjustment.getItemName(), adjustment.getQuantity(),
                adjustment.getAmount(), adjustment.getDetail(), adjustment.getActor(), adjustment.getApprovedBy(),
                adjustment.getCreatedAt());
    }
}
