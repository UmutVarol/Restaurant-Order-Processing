package com.umutvarol.restaurant.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** Where one branch differs from the central menu for one dish: its own price, or its own sold-out state. */
@Entity
@Table(name = "branch_menu_items")
public class BranchMenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "menu_item_id", nullable = false, updatable = false)
    private Long menuItemId;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    private Boolean available;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;

    /** When this branch's own price last changed. */
    @Column(name = "price_changed_at")
    private @Nullable Instant priceChangedAt;

    protected BranchMenuItem() {
        // for JPA
    }

    BranchMenuItem(long branchId, long menuItemId) {
        this.branchId = branchId;
        this.menuItemId = menuItemId;
    }

    void change(@Nullable BigDecimal price, @Nullable Boolean available, Instant at, String by) {
        if (price == null ? this.price != null : this.price == null || price.compareTo(this.price) != 0) {
            this.priceChangedAt = at;
        }
        this.price = price;
        this.available = available;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    boolean differs() {
        return price != null || available != null;
    }

    public Long getBranchId() {
        return branchId;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public @Nullable BigDecimal getPrice() {
        return price;
    }

    public @Nullable Instant getPriceChangedAt() {
        return priceChangedAt;
    }

    public @Nullable Boolean getAvailable() {
        return available;
    }
}
