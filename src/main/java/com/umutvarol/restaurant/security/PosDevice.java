package com.umutvarol.restaurant.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A till, tablet or kitchen screen a manager registered. Staff can switch users with a PIN only on such a device;
 * the device proves itself with a long random token of which only the SHA-256 is stored.
 */
@Entity
@Table(name = "pos_devices")
public class PosDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PosDevice() {
        // for JPA
    }

    PosDevice(String name, String tokenHash, String createdBy, Instant at) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = at;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    void seen(Instant at) {
        lastSeenAt = at;
    }

    void revoke(Instant at) {
        if (revokedAt == null) {
            revokedAt = at;
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }
}
