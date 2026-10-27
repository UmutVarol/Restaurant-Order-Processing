package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.order.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** The end-of-day close (Z report): what the cash drawer should hold, what was counted, and the difference. */
@Entity
@Table(name = "day_closings")
public class DayClosing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "expected_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedCash;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal variance;

    @Column(name = "card_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal cardTotal;

    @Column(name = "online_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal onlineTotal;

    @Column(name = "tips_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal tipsTotal;

    @Column(name = "refunds_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundsTotal;

    @Column(length = 255)
    private String note;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "closed_by", nullable = false, length = 50)
    private String closedBy;

    protected DayClosing() {
        // for JPA
    }

    DayClosing(LocalDate businessDate, DaySummary summary, BigDecimal countedCash, @Nullable String note,
               Instant at, String closedBy) {
        this.businessDate = businessDate;
        this.expectedCash = summary.expectedCash();
        this.countedCash = countedCash;
        this.variance = countedCash.subtract(summary.expectedCash());
        this.cardTotal = summary.totalFor(PaymentMethod.CARD);
        this.onlineTotal = summary.totalFor(PaymentMethod.ONLINE);
        this.tipsTotal = summary.tips();
        this.refundsTotal = summary.refunds();
        this.note = note;
        this.closedAt = at;
        this.closedBy = closedBy;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public BigDecimal getCountedCash() {
        return countedCash;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public String getNote() {
        return note;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }
}
