package com.umutvarol.restaurant.staff;

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
import org.hibernate.annotations.TenantId;
import java.math.BigDecimal;

/** One person's part of a tip pool. */
@Entity
@Table(name = "tip_shares")
public class TipShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pool_id")
    private TipPool pool;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false)
    private int minutes;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal weight;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    protected TipShare() {
        // for JPA
    }

    TipShare(TipPool pool, AppUser user, int minutes, BigDecimal weight, BigDecimal amount) {
        this.pool = pool;
        this.user = user;
        this.minutes = minutes;
        this.weight = weight;
        this.amount = amount;
    }

    public TipPool getPool() {
        return pool;
    }

    public AppUser getUser() {
        return user;
    }

    public int getMinutes() {
        return minutes;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
