package com.umutvarol.restaurant.guest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** How a business looks to its guests: its colour and logo. One row per business, keyed by it. */
@Entity
@Table(name = "guest_branding")
public class GuestBranding {

    @Id
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** {@code #rrggbb}, lower case; null keeps Sofra's own colour. */
    @Column(length = 7)
    private String accent;

    /** At most {@link BrandingService#MAX_LOGO_BYTES}; the manager's browser has already scaled it down. */
    @Column(name = "logo")
    private byte[] logo;

    @Column(name = "logo_type", length = 20)
    private String logoType;

    /** Goes up with every new logo, so guests' phones fetch it again (its address carries the number). */
    @Column(name = "logo_version", nullable = false)
    private int logoVersion;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Version
    private long version;

    protected GuestBranding() {
        // for JPA
    }

    static GuestBranding none(long businessId) {
        GuestBranding branding = new GuestBranding();
        branding.id = businessId;
        return branding;
    }

    void recolour(@Nullable String accent, Instant at, String by) {
        this.accent = accent;
        touched(at, by);
    }

    void replaceLogo(byte[] logo, String type, Instant at, String by) {
        this.logo = logo.clone();
        this.logoType = type;
        this.logoVersion++;
        touched(at, by);
    }

    void removeLogo(Instant at, String by) {
        this.logo = null;
        this.logoType = null;
        touched(at, by);
    }

    private void touched(Instant at, String by) {
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public @Nullable String getAccent() {
        return accent;
    }

    public byte @Nullable [] getLogo() {
        return logo == null ? null : logo.clone();
    }

    public @Nullable String getLogoType() {
        return logoType;
    }

    public boolean hasLogo() {
        return logoType != null;
    }

    public int getLogoVersion() {
        return logoVersion;
    }

    public @Nullable Instant getUpdatedAt() {
        return updatedAt;
    }

    public @Nullable String getUpdatedBy() {
        return updatedBy;
    }
}
