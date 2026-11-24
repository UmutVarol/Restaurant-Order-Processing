package com.umutvarol.restaurant.order.pricing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The monetary summary of an order. Stored with the order so that totals never change retroactively when
 * menu prices, tax rates or delivery fees are updated later.
 *
 * <p>Menu prices include VAT (KDV), so {@code total = subtotal - discount + deliveryFee} and {@code tax} is the VAT
 * within that total, listed by rate in {@link #vat()}. Orders placed before prices included VAT have
 * {@code taxIncluded = false}: for them {@code total = subtotal - discount + tax + deliveryFee}, tax added on top.
 */
@Embeddable
public record PriceBreakdown(
        @Column(name = "subtotal", nullable = false, precision = 12, scale = 2) BigDecimal subtotal,
        @Column(name = "discount", nullable = false, precision = 12, scale = 2) BigDecimal discount,
        @Column(name = "tax", nullable = false, precision = 12, scale = 2) BigDecimal tax,
        @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 2) BigDecimal deliveryFee,
        @Column(name = "total", nullable = false, precision = 12, scale = 2) BigDecimal total,
        @JsonIgnore @Column(name = "vat_lines", length = 255) @Nullable String vatLines,
        @Column(name = "tax_included", nullable = false) boolean taxIncluded) {

    /** The VAT within the total by rate; empty for orders placed before prices included VAT. */
    @JsonProperty("vat")
    public List<VatLine> vat() {
        return VatLine.decode(vatLines);
    }
}
