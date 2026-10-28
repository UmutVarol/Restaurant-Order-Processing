package com.umutvarol.restaurant.inventory.dto;

import com.umutvarol.restaurant.inventory.StockUnit;
import java.math.BigDecimal;

/**
 * @param bulkUnit what {@code cost} is per: "kg", "l" or "piece"
 * @param onHand   in base units, from the ledger
 * @param value    what is on hand is worth at the current price
 */
public record IngredientResponse(Long id, String name, StockUnit unit, String bulkUnit, BigDecimal cost,
                                 BigDecimal lowStockLevel, boolean trackStock, BigDecimal onHand, BigDecimal value,
                                 boolean low, boolean out) {
}
