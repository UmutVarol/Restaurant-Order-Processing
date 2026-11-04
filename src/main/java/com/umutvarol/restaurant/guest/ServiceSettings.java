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
import org.jspecify.annotations.Nullable;

/** The restaurant's one row of service settings: the mode and the rules for carts sent from tables. */
@Entity
@Table(name = "service_settings")
public class ServiceSettings {

    static final short ID = 1;

    @Id
    private Short id;

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
