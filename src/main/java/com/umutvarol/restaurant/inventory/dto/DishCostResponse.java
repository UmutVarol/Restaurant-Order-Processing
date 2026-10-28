package com.umutvarol.restaurant.inventory.dto;

import java.math.BigDecimal;

/** What one portion of a dish costs to make, for the menu screen. */
public record DishCostResponse(Long menuItemId, BigDecimal cost, BigDecimal foodCostPercent) {
}
