package com.umutvarol.restaurant.coupon;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of checking a coupon code: either the applicable {@link Coupon} or the reason it was rejected.
 *
 * <p>Returning a result instead of throwing lets callers that merely <em>preview</em> a coupon (the live
 * quote at the till) report the problem without the exception crossing a transactional boundary, which would
 * mark the caller's transaction as rollback-only.
 */
public record CouponValidation(String code, @Nullable Coupon coupon, @Nullable String errorCode,
                               @Nullable String message, Map<String, Object> parameters) {

    static CouponValidation valid(Coupon coupon) {
        return new CouponValidation(coupon.getCode(), coupon, null, null, Map.of());
    }

    static CouponValidation invalid(String code, BusinessRuleException reason) {
        return new CouponValidation(code, null, reason.getCode(), reason.getMessage(), reason.getParameters());
    }

    public boolean isValid() {
        return coupon != null;
    }

    /** The coupon, or the rejection reason as a {@link BusinessRuleException}. */
    public Coupon orElseThrow() {
        if (coupon == null) {
            throw new BusinessRuleException(errorCode, message, parameters);
        }
        return coupon;
    }
}
