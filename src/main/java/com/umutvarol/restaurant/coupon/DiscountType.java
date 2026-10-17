package com.umutvarol.restaurant.coupon;

import com.umutvarol.restaurant.common.money.Money;
import java.math.BigDecimal;

/**
 * How a coupon's value is turned into a discount — a small Strategy pattern expressed as an enum.
 */
public enum DiscountType {

    /** {@code value} is a percentage of the subtotal, e.g. {@code 15} means 15% off. */
    PERCENTAGE {
        @Override
        BigDecimal rawDiscount(BigDecimal subtotal, BigDecimal value) {
            return Money.percentOf(subtotal, value.movePointLeft(2));
        }
    },

    /** {@code value} is a fixed amount in lira taken off the subtotal. */
    FIXED_AMOUNT {
        @Override
        BigDecimal rawDiscount(BigDecimal subtotal, BigDecimal value) {
            return Money.normalize(value);
        }
    };

    abstract BigDecimal rawDiscount(BigDecimal subtotal, BigDecimal value);

    /** The discount for the given subtotal, never negative and never more than the subtotal itself. */
    public BigDecimal discountFor(BigDecimal subtotal, BigDecimal value) {
        BigDecimal discount = rawDiscount(subtotal, value);
        return discount.max(Money.ZERO).min(Money.normalize(subtotal));
    }
}
