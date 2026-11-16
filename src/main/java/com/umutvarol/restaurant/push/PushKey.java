package com.umutvarol.restaurant.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The server's VAPID key pair (one row, server-wide). */
@Entity
@Table(name = "push_keys")
class PushKey {

    static final long ID = 1L;

    @Id
    private Long id;

    @Column(name = "public_key", nullable = false, length = 120)
    private String publicKey;

    /** Sealed with the server's encryption key when it has one ("sealed:" prefix); otherwise as is ("plain:"). */
    @Column(name = "private_key", nullable = false, length = 600)
    private String privateKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushKey() {
        // for JPA
    }

    PushKey(String publicKey, String privateKey, Instant at) {
        this.id = ID;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.createdAt = at;
    }

    String getPublicKey() {
        return publicKey;
    }

    String getPrivateKey() {
        return privateKey;
    }
}
