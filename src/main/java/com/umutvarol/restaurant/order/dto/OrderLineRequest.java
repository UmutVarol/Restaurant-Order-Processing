package com.umutvarol.restaurant.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OrderLineRequest(
        @NotNull Long menuItemId,
        @NotNull @Min(1) @Max(50) Integer quantity,
        @Size(max = 140) String note) {
}
