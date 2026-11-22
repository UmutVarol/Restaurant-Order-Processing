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

/** A verified visit's rating: given once, for a bill that was paid. The restaurant may answer it in public. */
@Entity
@Table(name = "visit_ratings")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class VisitRating implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "bill_id", nullable = false, unique = true, updatable = false)
    private Long billId;

    @Column(nullable = false, updatable = false)
    private short stars;

    @Column(length = 200, updatable = false)
    private String tags;

    @Column(length = 400, updatable = false)
    private String liked;

    @Column(length = 500, updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 500)
    private String reply;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "replied_by", length = 50)
    private String repliedBy;

    protected VisitRating() {
        // for JPA
    }

    VisitRating(long branchId, long billId, int stars, @Nullable String tags, @Nullable String liked,
                @Nullable String comment, Instant at) {
        this.branchId = branchId;
        this.billId = billId;
        this.stars = (short) stars;
        this.tags = tags;
        this.liked = liked;
        this.comment = comment;
        this.createdAt = at;
    }

    void answer(@Nullable String reply, String by, Instant at) {
        this.reply = reply;
        this.repliedBy = reply == null ? null : by;
        this.repliedAt = reply == null ? null : at;
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

    public Long getBillId() {
        return billId;
    }

    public int getStars() {
        return stars;
    }

    public @Nullable String getTags() {
        return tags;
    }

    public @Nullable String getLiked() {
        return liked;
    }

    public @Nullable String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable String getReply() {
        return reply;
    }

    public @Nullable Instant getRepliedAt() {
        return repliedAt;
    }
}
