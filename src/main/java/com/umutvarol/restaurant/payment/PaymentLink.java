package com.umutvarol.restaurant.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A link (shown as a QR code at the table) that lets a guest pay part or all of a bill from their own phone.
 * Only a hash of the secret token is stored; the token itself is shown once, when the link is created.
 */
@Entity
@Table(name = "payment_links")
public class PaymentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** A fixed share for one guest, or {@code null} to let the guest pay whatever is still due. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    protected PaymentLink() {
        // for JPA
    }

    PaymentLink(Bill bill, String tokenHash, @Nullable BigDecimal amount, Instant expiresAt, Instant at, String createdBy) {
        this.bill = bill;
        this.tokenHash = tokenHash;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.createdAt = at;
        this.createdBy = createdBy;
    }

    public boolean isUsable(Instant now) {
        return !revoked && now.isBefore(expiresAt) && bill.getStatus() == BillStatus.OPEN;
    }

    void revoke() {
        this.revoked = true;
    }

    public Long getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
