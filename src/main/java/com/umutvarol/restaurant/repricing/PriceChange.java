package com.umutvarol.restaurant.repricing;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** One bulk price change: how it was made and every price it moved, so it can be read back and undone. */
@Entity
@Table(name = "price_changes")
public class PriceChange {

    public enum Mode {
        /** Every price by the same percentage. */
        PERCENT,
        /** Every price by the same amount in lira. */
        AMOUNT,
        /** Each price to a given value (the suggested prices of the margin check). */
        EXACT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Mode mode;

    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Rounding rounding;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    @Column(name = "reverted_at")
    private @Nullable Instant revertedAt;

    @Column(name = "reverted_by", length = 50)
    private @Nullable String revertedBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "change_id", nullable = false)
    private List<PriceChangeLine> lines = new ArrayList<>();

    protected PriceChange() {
        // for JPA
    }

    PriceChange(Mode mode, @Nullable BigDecimal amount, Rounding rounding, Instant at, String by) {
        this.mode = mode;
        this.amount = amount;
        this.rounding = rounding;
        this.createdAt = at;
        this.createdBy = by;
    }

    void add(Long itemId, @Nullable Long branchId, BigDecimal oldPrice, BigDecimal newPrice) {
        lines.add(new PriceChangeLine(itemId, branchId, oldPrice, newPrice));
        itemCount = (int) lines.stream().map(PriceChangeLine::getMenuItemId).distinct().count();
    }

    void reverted(Instant at, String by) {
        this.revertedAt = at;
        this.revertedBy = by;
    }

    public Long getId() {
        return id;
    }

    public Mode getMode() {
        return mode;
    }

    public @Nullable BigDecimal getAmount() {
        return amount;
    }

    public Rounding getRounding() {
        return rounding;
    }

    public int getItemCount() {
        return itemCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public @Nullable Instant getRevertedAt() {
        return revertedAt;
    }

    public @Nullable String getRevertedBy() {
        return revertedBy;
    }

    public List<PriceChangeLine> getLines() {
        return lines;
    }
}
