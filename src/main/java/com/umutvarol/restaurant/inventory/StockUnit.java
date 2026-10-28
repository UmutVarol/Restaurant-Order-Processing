package com.umutvarol.restaurant.inventory;

import java.math.BigDecimal;

/**
 * The unit an ingredient is counted in. Recipes and the ledger use the small base unit (grams, millilitres,
 * pieces); prices are per bulk unit (kilogram, litre, piece), the way suppliers quote them.
 */
public enum StockUnit {
    GRAM("kg", 1000),
    MILLILITRE("l", 1000),
    PIECE("piece", 1);

    private final String bulkUnit;
    private final BigDecimal perBulkUnit;

    StockUnit(String bulkUnit, int perBulkUnit) {
        this.bulkUnit = bulkUnit;
        this.perBulkUnit = BigDecimal.valueOf(perBulkUnit);
    }

    /** What the price is quoted per: "kg", "l" or "piece". */
    public String bulkUnit() {
        return bulkUnit;
    }

    /** Base units in one bulk unit: 1000 grams in a kilogram. */
    public BigDecimal perBulkUnit() {
        return perBulkUnit;
    }
}
