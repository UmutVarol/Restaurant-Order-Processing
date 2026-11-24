package com.umutvarol.restaurant.order.pricing;

import java.math.BigDecimal;

/**
 * What one order line charges, VAT included, and the VAT rate it is charged at.
 *
 * @param rate  the line's VAT rate as a percentage, e.g. {@code 10.00}
 * @param gross the line total, VAT included
 */
public record TaxedAmount(BigDecimal rate, BigDecimal gross) {
}
