package com.umutvarol.restaurant.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Theoretical against actual use over a period. Sold is what recipes say completed orders used; waste was
 * recorded with a reason; the count difference is what counts found missing (negative) or extra that nothing
 * explains: the variance. Values are at the prices of the time.
 */
public record StockReportResponse(Instant from, Instant to, List<Row> rows, BigDecimal soldCost, BigDecimal wasteCost,
                                  BigDecimal deliveredCost, BigDecimal varianceCost) {

    /** Quantities are in base units and positive, except the count difference, which keeps its sign. */
    public record Row(Long ingredientId, String name, String unit, BigDecimal sold, BigDecimal soldCost,
                      BigDecimal wasted, BigDecimal wasteCost, BigDecimal delivered, BigDecimal deliveredCost,
                      BigDecimal countDifference, BigDecimal varianceCost, BigDecimal onHand) {
    }
}
