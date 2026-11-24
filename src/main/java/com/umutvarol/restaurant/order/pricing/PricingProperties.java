package com.umutvarol.restaurant.order.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pricing rules, configurable per deployment. Menu prices include VAT (KDV); each dish carries its own rate.
 *
 * @param taxRate               the VAT rate orders placed before prices included VAT were priced with, and are
 *                              still repriced with, e.g. {@code 0.10} (a new dish starts at 10 %; each dish has its own)
 * @param deliveryVatRate       the VAT rate within the delivery fee, e.g. {@code 0.10} (delivering a meal is part
 *                              of the meal's sale)
 * @param deliveryFee           flat fee charged on delivery orders, VAT included
 * @param freeDeliveryThreshold delivery is free when the discounted subtotal (VAT included) reaches this amount
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.pricing")
public record PricingProperties(
        @DefaultValue("0.10") @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal taxRate,
        @DefaultValue("0.10") @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal deliveryVatRate,
        @DefaultValue("39.90") @NotNull @DecimalMin("0.00") BigDecimal deliveryFee,
        @DefaultValue("750.00") @NotNull @DecimalMin("0.00") BigDecimal freeDeliveryThreshold) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    BigDecimal deliveryVatPercent() {
        return deliveryVatRate.multiply(HUNDRED).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
