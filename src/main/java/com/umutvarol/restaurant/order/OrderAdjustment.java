package com.umutvarol.restaurant.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One change to an order after it was placed, kept for the audit trail and the order's timeline. */
@Entity
@Table(name = "order_adjustments")
public class OrderAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "line_id")
    private Long lineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjustmentKind kind;

    @Column(name = "item_name", length = 80)
    private String itemName;

    private Integer quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false, length = 50)
    private String actor;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderAdjustment() {
        // for JPA
    }

    OrderAdjustment(Order order, AdjustmentKind kind, @Nullable OrderLine line, @Nullable Integer quantity,
                    @Nullable BigDecimal amount, @Nullable String detail, String actor, @Nullable String approvedBy,
                    Instant at) {
        this.order = order;
        this.kind = kind;
        this.lineId = line == null ? null : line.getId();
        this.itemName = line == null ? null : line.getItemName();
        this.quantity = quantity;
        this.amount = amount;
        this.detail = detail;
        this.actor = actor;
        this.approvedBy = approvedBy;
        this.createdAt = at;
    }

    public Long getId() {
        return id;
    }

    public AdjustmentKind getKind() {
        return kind;
    }

    public @Nullable Long getLineId() {
        return lineId;
    }

    public @Nullable String getItemName() {
        return itemName;
    }

    public @Nullable Integer getQuantity() {
        return quantity;
    }

    public @Nullable BigDecimal getAmount() {
        return amount;
    }

    public @Nullable String getDetail() {
        return detail;
    }

    public String getActor() {
        return actor;
    }

    public @Nullable String getApprovedBy() {
        return approvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
