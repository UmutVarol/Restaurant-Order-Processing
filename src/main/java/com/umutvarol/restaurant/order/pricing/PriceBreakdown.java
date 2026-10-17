package com.umutvarol.restaurant.order.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * The monetary summary of an order. Stored with the order so that totals never change retroactively when
 * menu prices, tax rates or delivery fees are updated later.
 *
 * <p>{@code total = subtotal - discount + tax + deliveryFee}, where tax is applied to the discounted subtotal.
 */
@Embeddable
public record PriceBreakdown(
        @Column(name = "subtotal", nullable = false, precision = 12, scale = 2) BigDecimal subtotal,
        @Column(name = "discount", nullable = false, precision = 12, scale = 2) BigDecimal discount,
        @Column(name = "tax", nullable = false, precision = 12, scale = 2) BigDecimal tax,
        @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2) BigDecimal deliveryFee,
        @Column(name = "total", nullable = false, precision = 12, scale = 2) BigDecimal total) {
}
