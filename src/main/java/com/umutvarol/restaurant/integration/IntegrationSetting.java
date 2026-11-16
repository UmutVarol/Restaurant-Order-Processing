package com.umutvarol.restaurant.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** One connection of a business: an address, a sealed key and, for a platform, its sealed webhook secret. */
@Entity
@Table(name = "integration_settings")
public class IntegrationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private IntegrationKind kind;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    /** Sealed (SecretBox). */
    @Column(name = "api_key", length = 600)
    private String apiKey;

    /** Sealed (SecretBox). */
    @Column(name = "webhook_secret", length = 600)
    private String webhookSecret;

    @Column(name = "key_hint", length = 8)
    private String keyHint;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;

    @Version
    private long version;

    protected IntegrationSetting() {
        // for JPA
    }

    IntegrationSetting(IntegrationKind kind) {
        this.kind = kind;
    }

    void change(@Nullable String baseUrl, @Nullable String sealedKey, @Nullable String sealedSecret, @Nullable String hint,
                Instant at, String by) {
        this.baseUrl = baseUrl;
        this.apiKey = sealedKey;
        this.webhookSecret = sealedSecret;
        this.keyHint = hint;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public Long getId() {
        return id;
    }

    public IntegrationKind getKind() {
        return kind;
    }

    public @Nullable String getBaseUrl() {
        return baseUrl;
    }

    @Nullable String getApiKey() {
        return apiKey;
    }

    @Nullable String getWebhookSecret() {
        return webhookSecret;
    }

    public @Nullable String getKeyHint() {
        return keyHint;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version;
    }
}
