package com.umutvarol.restaurant.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderLineRequest(
        @NotNull Long menuItemId,
        @NotNull @Min(1) @Max(50) Integer quantity,
        @Size(max = 140) String note,
        @Size(max = 20) List<@NotNull Long> optionIds,
        @Min(1) @Max(3) Integer course) {

    /** A line in the dish's usual course. */
    public OrderLineRequest(Long menuItemId, Integer quantity, String note, List<Long> optionIds) {
        this(menuItemId, quantity, note, optionIds, null);
    }
}
