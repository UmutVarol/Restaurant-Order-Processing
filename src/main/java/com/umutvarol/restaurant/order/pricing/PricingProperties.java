package com.umutvarol.restaurant.order.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pricing rules, configurable per deployment.
 *
 * @param taxRate               VAT (KDV) rate applied to the discounted subtotal, e.g. {@code 0.10}
 * @param deliveryFee           flat fee charged on delivery orders
 * @param freeDeliveryThreshold delivery is free when the discounted subtotal reaches this amount
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.pricing")
public record PricingProperties(
        @DefaultValue("0.10") @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal taxRate,
        @DefaultValue("39.90") @NotNull @DecimalMin("0.00") BigDecimal deliveryFee,
        @DefaultValue("750.00") @NotNull @DecimalMin("0.00") BigDecimal freeDeliveryThreshold) {
}
