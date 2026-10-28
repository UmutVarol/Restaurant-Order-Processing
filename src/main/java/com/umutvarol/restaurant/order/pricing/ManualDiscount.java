package com.umutvarol.restaurant.order.pricing;

import com.umutvarol.restaurant.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * A discount a manager gives on an order, as a percentage or a fixed amount (exactly one is set). It applies after
 * any coupon, and the two together never take more than the subtotal.
 */
public record ManualDiscount(@Nullable BigDecimal percent, @Nullable BigDecimal amount) {

    public ManualDiscount {
        if ((percent == null) == (amount == null)) {
            throw new IllegalArgumentException("Exactly one of percent and amount must be set");
        }
    }

    /** The discount on what is left after the coupon. */
    public BigDecimal discountFor(BigDecimal remaining) {
        BigDecimal value = percent != null
                ? remaining.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : amount;
        return Money.normalize(value.min(remaining).max(BigDecimal.ZERO));
    }
}
