package com.umutvarol.restaurant.payment;

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

/** Money given back from a payment: cash from the drawer, a refund on the card terminal, or an online refund. */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "provider_refund_id", length = 120)
    private String providerRefundId;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    protected Refund() {
        // for JPA
    }

    Refund(Payment payment, BigDecimal amount, RefundStatus status, String reason, Instant at, String createdBy) {
        this.payment = payment;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = at;
        this.createdBy = createdBy;
    }

    void succeed(@Nullable String providerRefundId) {
        this.status = RefundStatus.SUCCEEDED;
        this.providerRefundId = providerRefundId;
    }

    void fail(String reason) {
        this.status = RefundStatus.FAILED;
        this.failureReason = reason;
        payment.refundFailed(this);
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
