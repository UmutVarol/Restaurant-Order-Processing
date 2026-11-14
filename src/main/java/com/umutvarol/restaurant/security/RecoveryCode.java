package com.umutvarol.restaurant.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import java.time.Instant;

/** A one-time code that stands in for the authenticator app, kept as a bcrypt hash. */
@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RecoveryCode() {
        // for JPA
    }

    RecoveryCode(Long userId, String codeHash, Instant at) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = at;
    }

    String getCodeHash() {
        return codeHash;
    }

    boolean isUsed() {
        return usedAt != null;
    }

    void use(Instant at) {
        this.usedAt = at;
    }
}
