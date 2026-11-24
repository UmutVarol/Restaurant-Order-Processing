package com.umutvarol.restaurant.repricing;

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
import org.hibernate.annotations.TenantId;

/** The food cost a business aims for and how it rounds new prices; one row per business, created when first saved. */
@Entity
@Table(name = "pricing_settings")
public class PricingSettings {

    static final BigDecimal DEFAULT_TARGET = BigDecimal.valueOf(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "target_food_cost", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetFoodCost = DEFAULT_TARGET;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Rounding rounding = Rounding.ONE;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;

    protected PricingSettings() {
        // for JPA
    }

    PricingSettings(Instant at, String by) {
        this.updatedAt = at;
        this.updatedBy = by;
    }

    void change(BigDecimal targetFoodCost, Rounding rounding, Instant at, String by) {
        this.targetFoodCost = targetFoodCost;
        this.rounding = rounding;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public BigDecimal getTargetFoodCost() {
        return targetFoodCost;
    }

    public Rounding getRounding() {
        return rounding;
    }
}
