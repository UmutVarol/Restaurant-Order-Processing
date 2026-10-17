package com.umutvarol.restaurant.coupon;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponResponse(
        String code,
        String description,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minimumSubtotal,
        BigDecimal maxDiscount,
        Instant validUntil) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getCode(),
                coupon.getDescription(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinimumSubtotal(),
                coupon.getMaxDiscount(),
                coupon.getValidUntil());
    }
}
