package com.umutvarol.restaurant.coupon;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository repository;
    private final Clock clock;

    public CouponService(CouponRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Checks whether the coupon with the given code (case-insensitive) can be applied to the given subtotal.
     * Never throws for an unusable coupon — see {@link CouponValidation}.
     */
    public CouponValidation validate(String code, BigDecimal subtotal) {
        String normalized = Coupon.normalizeCode(code);
        try {
            Coupon coupon = repository.findByCode(normalized)
                    .orElseThrow(() -> new BusinessRuleException("COUPON_NOT_FOUND",
                            "Coupon %s does not exist".formatted(normalized), Map.of("code", normalized)));
            coupon.assertApplicable(subtotal, clock.instant());
            return CouponValidation.valid(coupon);
        } catch (BusinessRuleException ex) {
            return CouponValidation.invalid(normalized, ex);
        }
    }

    /** Coupons staff can currently offer to guests. */
    public List<CouponResponse> listRedeemable() {
        Instant now = clock.instant();
        return repository.findByActiveTrueOrderByCodeAsc().stream()
                .filter(c -> c.getValidUntil() == null || now.isBefore(c.getValidUntil()))
                .map(CouponResponse::from)
                .toList();
    }
}
