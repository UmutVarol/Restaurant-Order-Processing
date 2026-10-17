package com.umutvarol.restaurant.order.pricing;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.order.OrderType;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for order pricing. Both the live quote shown at the till and the order that is
 * finally persisted are priced here, so the guest is always charged exactly what they were shown.
 */
@Component
public class PricingCalculator {

    private final PricingProperties properties;

    public PricingCalculator(PricingProperties properties) {
        this.properties = properties;
    }

    public PriceBreakdown calculate(BigDecimal subtotal, OrderType type, @Nullable Coupon coupon) {
        BigDecimal normalizedSubtotal = Money.normalize(subtotal);
        BigDecimal discount = coupon == null ? Money.ZERO : coupon.discountFor(normalizedSubtotal);
        BigDecimal discountedSubtotal = normalizedSubtotal.subtract(discount);
        BigDecimal tax = Money.percentOf(discountedSubtotal, properties.taxRate());
        BigDecimal deliveryFee = deliveryFee(discountedSubtotal, type);
        BigDecimal total = discountedSubtotal.add(tax).add(deliveryFee);
        return new PriceBreakdown(normalizedSubtotal, discount, tax, deliveryFee, Money.normalize(total));
    }

    /** How much more the guest needs to order to get free delivery; zero when it already applies. */
    public BigDecimal amountUntilFreeDelivery(PriceBreakdown pricing, OrderType type) {
        if (type != OrderType.DELIVERY) {
            return Money.ZERO;
        }
        BigDecimal discountedSubtotal = pricing.subtotal().subtract(pricing.discount());
        return Money.normalize(properties.freeDeliveryThreshold().subtract(discountedSubtotal).max(BigDecimal.ZERO));
    }

    public BigDecimal freeDeliveryThreshold() {
        return Money.normalize(properties.freeDeliveryThreshold());
    }

    private BigDecimal deliveryFee(BigDecimal discountedSubtotal, OrderType type) {
        boolean qualifiesForFreeDelivery = discountedSubtotal.compareTo(properties.freeDeliveryThreshold()) >= 0;
        return type == OrderType.DELIVERY && !qualifiesForFreeDelivery
                ? Money.normalize(properties.deliveryFee())
                : Money.ZERO;
    }
}
