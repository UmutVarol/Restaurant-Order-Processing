package com.umutvarol.restaurant.inventory;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** A unit a quantity can be typed in. Each converts exactly into one base unit; nothing goes through floating point. */
public enum QuantityUnit {
    G(StockUnit.GRAM, 1),
    KG(StockUnit.GRAM, 1000),
    ML(StockUnit.MILLILITRE, 1),
    L(StockUnit.MILLILITRE, 1000),
    PIECE(StockUnit.PIECE, 1);

    /** Base quantities keep three decimals (a milligram, a tenth of a millilitre is plenty). */
    public static final int SCALE = 3;

    private final StockUnit base;
    private final BigDecimal factor;

    QuantityUnit(StockUnit base, int factor) {
        this.base = base;
        this.factor = BigDecimal.valueOf(factor);
    }

    /**
     * @return {@code quantity} in the base units of {@code target}
     * @throws BusinessRuleException {@code UNIT_MISMATCH} for litres of flour or kilograms of eggs
     */
    public BigDecimal toBase(BigDecimal quantity, StockUnit target) {
        if (base != target) {
            throw new BusinessRuleException("UNIT_MISMATCH", "%s cannot be counted in %s".formatted(target, this),
                    Map.of("unit", name(), "expected", target.name()));
        }
        return quantity.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
