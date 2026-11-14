package com.umutvarol.restaurant.staff;

import org.hibernate.annotations.Filter;
import jakarta.persistence.EntityListeners;
import com.umutvarol.restaurant.business.BranchStamp;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.security.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** A planned shift on the rota. */
@Entity
@Table(name = "shifts")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class Shift implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** The branch this happened in (BranchOwned); lists are limited to it when a request works in one branch. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 80)
    private String note;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Shift() {
        // for JPA
    }

    Shift(AppUser user, Instant startsAt, Instant endsAt, @Nullable String note, String createdBy, Instant now) {
        this.user = user;
        plan(startsAt, endsAt, note);
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void plan(Instant startsAt, Instant endsAt, @Nullable String note) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.note = note;
    }

    void reassign(AppUser user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public @Nullable String getNote() {
        return note;
    }

    @Override
    public @Nullable Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }
}
