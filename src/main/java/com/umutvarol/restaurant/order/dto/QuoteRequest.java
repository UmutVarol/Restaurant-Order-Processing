package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.OrderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A cart to be priced without placing an order — powers the live totals at the till. */
public record QuoteRequest(
        @NotNull OrderType type,
        @Size(max = 32) String couponCode,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull OrderLineRequest> items) {
}
