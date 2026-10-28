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
        @Size(max = 20) List<@NotNull Long> optionIds) {
}
