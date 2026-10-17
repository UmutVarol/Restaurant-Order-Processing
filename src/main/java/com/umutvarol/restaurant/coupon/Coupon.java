package com.umutvarol.restaurant.coupon;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** A promotional code that grants a discount on the order subtotal. */
@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 120)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "minimum_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumSubtotal;

    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "valid_until")
    private Instant validUntil;

    protected Coupon() {
        // for JPA
    }

    public Coupon(String code, String description, DiscountType discountType, BigDecimal discountValue,
                  BigDecimal minimumSubtotal, BigDecimal maxDiscount, Instant validUntil) {
        this.code = normalizeCode(code);
        this.description = description;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minimumSubtotal = Money.normalize(minimumSubtotal);
        this.maxDiscount = maxDiscount == null ? null : Money.normalize(maxDiscount);
        this.validUntil = validUntil;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Verifies the coupon can be applied to an order with the given subtotal at the given moment.
     *
     * @throws BusinessRuleException with code {@code COUPON_INACTIVE}, {@code COUPON_EXPIRED} or
     *                               {@code COUPON_MINIMUM_NOT_MET}
     */
    public void assertApplicable(BigDecimal subtotal, Instant now) {
        if (!active) {
            throw new BusinessRuleException("COUPON_INACTIVE", "Coupon %s is no longer active".formatted(code),
                    Map.of("code", code));
        }
        if (validUntil != null && now.isAfter(validUntil)) {
            throw new BusinessRuleException("COUPON_EXPIRED", "Coupon %s has expired".formatted(code),
                    Map.of("code", code));
        }
        if (subtotal.compareTo(minimumSubtotal) < 0) {
            throw new BusinessRuleException("COUPON_MINIMUM_NOT_MET",
                    "Coupon %s requires a subtotal of at least %s TL".formatted(code, minimumSubtotal.toPlainString()),
                    Map.of("code", code, "minimumSubtotal", minimumSubtotal));
        }
    }

    /** The discount this coupon grants on the given subtotal, honouring the optional cap. */
    public BigDecimal discountFor(BigDecimal subtotal) {
        BigDecimal discount = discountType.discountFor(subtotal, discountValue);
        return maxDiscount == null ? discount : discount.min(maxDiscount);
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public BigDecimal getMinimumSubtotal() {
        return minimumSubtotal;
    }

    public BigDecimal getMaxDiscount() {
        return maxDiscount;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getValidUntil() {
        return validUntil;
    }
}
