package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.PaymentMethod;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Money taken against a {@link Bill}. {@code amount} settles the bill; {@code tip} is kept apart because it is
 * not part of the sale (no VAT, not revenue). Created only through {@link Bill}, which checks the amounts.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tip;

    @Column(precision = 12, scale = 2)
    private BigDecimal tendered;

    @Column(name = "change_due", precision = 12, scale = 2)
    private BigDecimal changeDue;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = Money.ZERO;

    @Column(length = 20)
    private String provider;

    @Column(name = "provider_token", length = 120)
    private String providerToken;

    @Column(name = "provider_payment_id", length = 120)
    private String providerPaymentId;

    @Column(name = "approval_code", length = 20)
    private String approvalCode;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_link_id")
    private PaymentLink link;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "under_review", nullable = false)
    private boolean underReview;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<Refund> refunds = new ArrayList<>();

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    Payment(Bill bill, PaymentMethod method, PaymentStatus status, BigDecimal amount, BigDecimal tip,
            Instant at, String createdBy, @Nullable String idempotencyKey) {
        this.bill = bill;
        this.method = method;
        this.status = status;
        this.amount = Money.normalize(amount);
        this.tip = Money.normalize(tip);
        this.createdAt = at;
        this.completedAt = status == PaymentStatus.SUCCEEDED ? at : null;
        this.createdBy = createdBy;
        this.idempotencyKey = idempotencyKey;
    }

    void recordCash(BigDecimal tendered) {
        this.tendered = Money.normalize(tendered);
        this.changeDue = this.tendered.subtract(amount).subtract(tip);
    }

    void recordApprovalCode(@Nullable String approvalCode) {
        this.approvalCode = approvalCode;
    }

    void viaLink(@Nullable PaymentLink link) {
        this.link = link;
    }

    void startedOnline(String provider, @Nullable String providerToken) {
        this.provider = provider;
        this.providerToken = providerToken;
    }

    /**
     * The provider confirmed the payment. A payment that had already expired can still succeed: the guest may
     * have finished paying just as we gave up waiting, and the money has been taken either way.
     */
    /** The card was charged, but the provider's fraud team is still checking it: keep holding the amount. */
    void underReview(@Nullable String providerPaymentId) {
        requirePending();
        this.underReview = true;
        this.providerPaymentId = providerPaymentId;
    }

    void succeed(@Nullable String providerPaymentId, Instant at) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.EXPIRED) {
            throw new IllegalStateException("Payment %d is already %s".formatted(id, status));
        }
        this.underReview = false;
        this.status = PaymentStatus.SUCCEEDED;
        this.providerPaymentId = providerPaymentId;
        this.completedAt = at;
    }

    void fail(String reason, Instant at) {
        requirePending();
        this.underReview = false;
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = at;
    }

    void expire(Instant at) {
        expire(at, "The guest did not finish paying in time.");
    }

    void expire(Instant at, String reason) {
        requirePending();
        if (underReview) {
            throw new IllegalStateException("Payment %d is under the provider's fraud review".formatted(id));
        }
        this.status = PaymentStatus.EXPIRED;
        this.failureReason = reason;
        this.completedAt = at;
    }

    Refund refund(BigDecimal refundAmount, RefundStatus refundStatus, String reason, Instant at, String actor) {
        BigDecimal value = Money.normalize(refundAmount);
        if (status != PaymentStatus.SUCCEEDED || value.signum() <= 0 || value.compareTo(refundable()) > 0) {
            throw new IllegalArgumentException("Cannot refund %s of payment %d".formatted(value, id));
        }
        Refund refund = new Refund(this, value, refundStatus, reason, at, actor);
        refunds.add(refund);
        refundedAmount = refundedAmount.add(value);
        return refund;
    }

    /** A provider refund that did not go through gives the money back to the payment. */
    void refundFailed(Refund refund) {
        refundedAmount = refundedAmount.subtract(refund.getAmount());
    }

    /** What still counts towards the bill: the amount, minus anything refunded. */
    public BigDecimal settledAmount() {
        return status == PaymentStatus.SUCCEEDED ? amount.subtract(refundedAmount) : Money.ZERO;
    }

    public BigDecimal refundable() {
        return status == PaymentStatus.SUCCEEDED ? amount.subtract(refundedAmount) : Money.ZERO;
    }

    public boolean isUnderReview() {
        return underReview;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment %d is already %s".formatted(id, status));
        }
    }

    public Long getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTip() {
        return tip;
    }

    public BigDecimal getTendered() {
        return tendered;
    }

    public BigDecimal getChangeDue() {
        return changeDue;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderToken() {
        return providerToken;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getApprovalCode() {
        return approvalCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentLink getLink() {
        return link;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public List<Refund> getRefunds() {
        return Collections.unmodifiableList(refunds);
    }
}
