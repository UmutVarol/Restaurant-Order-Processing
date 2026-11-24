package com.umutvarol.restaurant.repricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** One price a bulk change moved: a dish's central price, or one branch's own. */
@Entity
@Table(name = "price_change_lines")
public class PriceChangeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "menu_item_id", nullable = false, updatable = false)
    private Long menuItemId;

    @Column(name = "branch_id", updatable = false)
    private @Nullable Long branchId;

    @Column(name = "old_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal newPrice;

    protected PriceChangeLine() {
        // for JPA
    }

    PriceChangeLine(Long menuItemId, @Nullable Long branchId, BigDecimal oldPrice, BigDecimal newPrice) {
        this.menuItemId = menuItemId;
        this.branchId = branchId;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public @Nullable Long getBranchId() {
        return branchId;
    }

    public BigDecimal getOldPrice() {
        return oldPrice;
    }

    public BigDecimal getNewPrice() {
        return newPrice;
    }
}
