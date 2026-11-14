package com.umutvarol.restaurant.guest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** A business's service settings: the mode and the rules for carts sent from tables. One row per business, keyed by it. */
@Entity
@Table(name = "service_settings")
public class ServiceSettings {

    @Id
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceMode mode;

    @Column(name = "auto_accept_limit", precision = 12, scale = 2)
    private BigDecimal autoAcceptLimit;

    @Column(name = "first_cart_needs_staff", nullable = false)
    private boolean firstCartNeedsStaff;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Version
    private long version;

    protected ServiceSettings() {
        // for JPA
    }

    /** What a new business starts with: guests order, a waiter checks the first cart and large ones. */
    static ServiceSettings defaults(long businessId) {
        ServiceSettings settings = new ServiceSettings();
        settings.id = businessId;
        settings.mode = ServiceMode.MIXED;
        settings.autoAcceptLimit = new BigDecimal("1500.00");
        settings.firstCartNeedsStaff = true;
        return settings;
    }

    void change(ServiceMode mode, @Nullable BigDecimal autoAcceptLimit, boolean firstCartNeedsStaff, Instant at,
                String by) {
        this.mode = mode;
        this.autoAcceptLimit = autoAcceptLimit;
        this.firstCartNeedsStaff = firstCartNeedsStaff;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public ServiceMode getMode() {
        return mode;
    }

    public @Nullable BigDecimal getAutoAcceptLimit() {
        return autoAcceptLimit;
    }

    public boolean isFirstCartNeedsStaff() {
        return firstCartNeedsStaff;
    }

    public @Nullable Instant getUpdatedAt() {
        return updatedAt;
    }

    public @Nullable String getUpdatedBy() {
        return updatedBy;
    }
}
