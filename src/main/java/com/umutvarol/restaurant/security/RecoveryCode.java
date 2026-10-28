package com.umutvarol.restaurant.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A one-time code that stands in for the authenticator app, kept as a bcrypt hash. */
@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
