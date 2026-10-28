package com.umutvarol.restaurant.table;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A physical table in the dining room that dine-in orders are served to. */
@Entity
@Table(name = "dining_tables")
public class DiningTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false)
    private int seats;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableZone zone;

    @Column(name = "qr_token_hash", length = 64)
    private String qrTokenHash;

    @Column(name = "qr_issued_at")
    private Instant qrIssuedAt;

    protected DiningTable() {
        // for JPA
    }

    public DiningTable(String code, int seats, TableZone zone) {
        this.code = code;
        this.seats = seats;
        this.zone = zone;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getSeats() {
        return seats;
    }

    public TableZone getZone() {
        return zone;
    }

    /** Replaces the table's QR code: the old printed code stops working. */
    public void issueQrToken(String tokenHash, Instant at) {
        this.qrTokenHash = tokenHash;
        this.qrIssuedAt = at;
    }

    public @Nullable Instant getQrIssuedAt() {
        return qrIssuedAt;
    }
}
