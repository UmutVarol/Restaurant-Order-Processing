package com.umutvarol.restaurant.staff;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A day's tips, shared out among the staff who worked it. Fixed once made: it is what payroll pays. */
@Entity
@Table(name = "tip_pools")
public class TipPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false, unique = true)
    private LocalDate businessDate;

    @Column(name = "tips_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal tipsTotal;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "pool", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TipShare> shares = new ArrayList<>();

    protected TipPool() {
        // for JPA
    }

    TipPool(LocalDate businessDate, BigDecimal tipsTotal, String createdBy, Instant now) {
        this.businessDate = businessDate;
        this.tipsTotal = tipsTotal;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    void add(TipShare share) {
        shares.add(share);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public BigDecimal getTipsTotal() {
        return tipsTotal;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<TipShare> getShares() {
        return shares;
    }
}
