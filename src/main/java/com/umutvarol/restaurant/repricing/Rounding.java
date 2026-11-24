package com.umutvarol.restaurant.repricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How new prices are rounded: to the nearest step, so a menu reads 185,00 rather than 184,63. */
public enum Rounding {
    NONE(null),
    HALF(new BigDecimal("0.50")),
    ONE(BigDecimal.ONE),
    FIVE(BigDecimal.valueOf(5)),
    TEN(BigDecimal.TEN);

    private final BigDecimal step;

    Rounding(BigDecimal step) {
        this.step = step;
    }

    /** The nearest step, never below one step (a price is never rounded to zero). */
    BigDecimal round(BigDecimal price) {
        if (step == null) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal steps = price.divide(step, 0, RoundingMode.HALF_UP).max(BigDecimal.ONE);
        return steps.multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

    /** The next step up: a suggested price never lands below what the target needs. */
    BigDecimal roundUp(BigDecimal price) {
        if (step == null) {
            return price.setScale(2, RoundingMode.CEILING);
        }
        return price.divide(step, 0, RoundingMode.CEILING).max(BigDecimal.ONE).multiply(step).setScale(2, RoundingMode.HALF_UP);
    }
}
