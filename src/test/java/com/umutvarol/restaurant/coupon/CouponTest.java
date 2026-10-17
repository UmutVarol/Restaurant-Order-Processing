package com.umutvarol.restaurant.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CouponTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void normalisesCodes() {
        Coupon coupon = new Coupon("  sofra20 ", "d", DiscountType.PERCENTAGE, new BigDecimal("20"), BigDecimal.ZERO,
                null, null);

        assertThat(coupon.getCode()).isEqualTo("SOFRA20");
        assertThat(Coupon.normalizeCode(null)).isEmpty();
    }

    @Test
    void acceptsAnApplicableCoupon() {
        Coupon coupon = coupon("400", NOW.plusSeconds(60));

        assertThatNoException().isThrownBy(() -> coupon.assertApplicable(new BigDecimal("400.00"), NOW));
    }

    @Test
    void rejectsSubtotalsBelowTheMinimum() {
        Coupon coupon = coupon("400", null);

        assertThatThrownBy(() -> coupon.assertApplicable(new BigDecimal("399.99"), NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("COUPON_MINIMUM_NOT_MET");
                    assertThat(ex.getParameters()).containsEntry("minimumSubtotal", new BigDecimal("400.00"));
                });
    }

    @Test
    void rejectsExpiredCoupons() {
        Coupon coupon = coupon("0", NOW.minusSeconds(1));

        assertThatThrownBy(() -> coupon.assertApplicable(BigDecimal.TEN, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COUPON_EXPIRED"));
    }

    @Test
    void rejectsDeactivatedCoupons() {
        Coupon coupon = coupon("0", null);
        coupon.deactivate();

        assertThatThrownBy(() -> coupon.assertApplicable(BigDecimal.TEN, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COUPON_INACTIVE"));
    }

    @Test
    void validationResultExposesTheReasonWithoutThrowing() {
        CouponValidation invalid = CouponValidation.invalid("NOPE",
                new BusinessRuleException("COUPON_NOT_FOUND", "Coupon NOPE does not exist"));

        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.errorCode()).isEqualTo("COUPON_NOT_FOUND");
        assertThatThrownBy(invalid::orElseThrow).isInstanceOf(BusinessRuleException.class)
                .hasMessage("Coupon NOPE does not exist");
    }

    private static Coupon coupon(String minimum, Instant validUntil) {
        return new Coupon("TATLI50", "50 TL off", DiscountType.FIXED_AMOUNT, new BigDecimal("50"),
                new BigDecimal(minimum), null, validUntil);
    }
}
