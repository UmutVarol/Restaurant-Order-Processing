package com.umutvarol.restaurant.printing;

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

/**
 * A print bridge: a small program in one branch that fetches that branch's tickets and prints them on the local
 * network. It signs in with a long random token, shown once; only the token's hash is stored.
 */
@Entity
@Table(name = "print_bridges")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class PrintBridge implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PrintBridge() {
        // for JPA
    }

    PrintBridge(String name, String tokenHash, Instant at, String createdBy) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = at;
        this.createdBy = createdBy;
    }

    void seen(Instant at) {
        this.lastSeenAt = at;
    }

    void revoke(Instant at) {
        this.revokedAt = at;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    @Override
    public Long getId() {
        return id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    @Override
    public @Nullable Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
