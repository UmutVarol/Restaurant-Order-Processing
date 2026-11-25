package com.umutvarol.restaurant.planning;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.BranchStamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** Something thrown away: portions of a dish or an amount of an ingredient, why, and what it was worth. */
@Entity
@Table(name = "waste_entries")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class WasteEntry implements BranchOwned {

    public enum Reason {
        /** Past its date. */
        EXPIRED,
        /** Went bad before its date. */
        SPOILED,
        /** Made more than was sold. */
        OVERPRODUCED,
        /** Sent back by a guest. */
        RETURNED,
        /** Dropped, spilled or burnt. */
        DROPPED,
        OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "menu_item_id", updatable = false)
    private @Nullable Long menuItemId;

    @Column(name = "ingredient_id", updatable = false)
    private @Nullable Long ingredientId;

    @Column(nullable = false, updatable = false, length = 120)
    private String name;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** "PORTION" for a dish, or the unit the quantity was typed in for an ingredient. */
    @Column(nullable = false, updatable = false, length = 10)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private Reason reason;

    @Column(updatable = false, length = 200)
    private @Nullable String note;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    @Column(name = "request_key", updatable = false, length = 64)
    private @Nullable String requestKey;

    protected WasteEntry() {
        // for JPA
    }

    WasteEntry(@Nullable Long menuItemId, @Nullable Long ingredientId, String name, BigDecimal quantity, String unit,
               Reason reason, @Nullable String note, BigDecimal cost, Instant createdAt, String createdBy,
               @Nullable String requestKey) {
        this.requestKey = requestKey;
        this.menuItemId = menuItemId;
        this.ingredientId = ingredientId;
        this.name = name;
        this.quantity = quantity;
        this.unit = unit;
        this.reason = reason;
        this.note = note;
        this.cost = cost;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }

    public @Nullable Long getMenuItemId() {
        return menuItemId;
    }

    public @Nullable Long getIngredientId() {
        return ingredientId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public Reason getReason() {
        return reason;
    }

    public @Nullable String getNote() {
        return note;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
