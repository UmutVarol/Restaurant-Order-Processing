package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import java.math.BigDecimal;
import java.util.List;

/**
 * Priced cart. An invalid coupon does not fail the quote — it is reported in {@link CouponResult} so the
 * till can show why it was not applied while still displaying totals.
 */
public record QuoteResponse(
        List<OrderLineResponse> lines,
        PriceBreakdown pricing,
        CouponResult coupon,
        BigDecimal freeDeliveryThreshold,
        BigDecimal amountUntilFreeDelivery) {

    public record CouponResult(String code, boolean applied, String description, String errorCode, String message) {
    }
}
