package com.umutvarol.restaurant.directory;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.BranchStamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** A guest saying "I am here" in the guest app. The device is only a hash, so a return visit counts without a name. */
@Entity
@Table(name = "venue_checkins")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class VenueCheckin implements BranchOwned {

    /** How the guest found the place. */
    public enum Method { NEARBY, LIST, QR, NFC, LINK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "device_hash", nullable = false, length = 64, updatable = false)
    private String deviceHash;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Method method;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VenueCheckin() {
        // for JPA
    }

    VenueCheckin(long branchId, String deviceHash, Method method, Instant at) {
        this.branchId = branchId;
        this.deviceHash = deviceHash;
        this.method = method;
        this.createdAt = at;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public @Nullable Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }

    @Override
    public @Nullable Long branchHint() {
        return branchId;
    }

    public String getDeviceHash() {
        return deviceHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
