package com.umutvarol.restaurant.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.TenantId;

/**
 * A business's own online payment account: its iyzico keys, sealed (never stored or shown in clear), and whether
 * they are the sandbox's. Keyed by the business, like the service settings.
 */
@Entity
@Table(name = "payment_settings")
public class PaymentSettings {

    public static final String IYZICO = "IYZICO";

    @Id
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "api_key", nullable = false, length = 600)
    private String apiKey;

    @Column(name = "secret_key", nullable = false, length = 600)
    private String secretKey;

    /** The last characters of the API key, to recognise which account is set up. */
    @Column(name = "key_hint", nullable = false, length = 8)
    private String keyHint;

    @Column(nullable = false)
    private boolean sandbox;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;

    protected PaymentSettings() {
        // for JPA
    }

    PaymentSettings(long business) {
        this.id = business;
        this.provider = IYZICO;
    }

    void change(String sealedApiKey, String sealedSecretKey, String keyHint, boolean sandbox, Instant now, String actor) {
        this.apiKey = sealedApiKey;
        this.secretKey = sealedSecretKey;
        this.keyHint = keyHint;
        this.sandbox = sandbox;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    String getApiKey() {
        return apiKey;
    }

    String getSecretKey() {
        return secretKey;
    }

    public String getKeyHint() {
        return keyHint;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
