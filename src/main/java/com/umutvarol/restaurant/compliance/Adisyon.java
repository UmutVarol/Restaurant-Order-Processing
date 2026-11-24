package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.BranchStamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * The e-Adisyon of one table's bill (VUK 509). Opened with the table's first order, it follows what the table orders
 * and is closed with the legal document of the payment. Every change is a new version for the integrator; a version
 * that was not accepted is retried with growing pauses, and the latest version always wins.
 */
@Entity
@Table(name = "adisyons")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class Adisyon implements BranchOwned {

    public enum Status {
        OPEN,
        /** Paid and closed with its legal document. */
        CLOSED,
        /** Everything on it was cancelled. */
        CANCELLED
    }

    /** Whether the latest version reached the integrator. */
    public enum Delivery {
        PENDING,
        SENT,
        /** Refused or unanswered; tried again later. */
        FAILED
    }

    static final int MAX_ATTEMPTS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "bill_id", nullable = false, updatable = false, unique = true)
    private Long billId;

    @Column(nullable = false, updatable = false, length = 36)
    private String ettn;

    @Column(name = "table_code", length = 20)
    private @Nullable String tableCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(name = "vat_lines", length = 255)
    private @Nullable String vatLines;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "fiscal_number", length = 40)
    private @Nullable String fiscalNumber;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private @Nullable Instant closedAt;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "sent_version", nullable = false)
    private int sentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Delivery delivery = Delivery.PENDING;

    @Column(length = 20)
    private @Nullable String provider;

    @Column(name = "document_number", length = 40)
    private @Nullable String documentNumber;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 255)
    private @Nullable String lastError;

    @Column(name = "next_attempt_at")
    private @Nullable Instant nextAttemptAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected Adisyon() {
        // for JPA
    }

    static Adisyon open(long billId, long branchId, @Nullable String tableCode, Instant openedAt, Instant now) {
        Adisyon adisyon = new Adisyon();
        adisyon.billId = billId;
        adisyon.branchId = branchId;
        adisyon.ettn = UUID.randomUUID().toString();
        adisyon.tableCode = tableCode;
        adisyon.status = Status.OPEN;
        adisyon.total = BigDecimal.ZERO;
        adisyon.tax = BigDecimal.ZERO;
        adisyon.openedAt = openedAt;
        adisyon.updatedAt = now;
        adisyon.nextAttemptAt = now;
        return adisyon;
    }

    /**
     * Brings the adisyon up to date with its bill. A change makes a new version, due to be sent now.
     *
     * @return whether anything changed
     */
    boolean follow(Status status, @Nullable String tableCode, BigDecimal total, BigDecimal tax, @Nullable String vatLines,
                   int itemCount, @Nullable String fiscalNumber, @Nullable Instant closedAt, Instant now) {
        boolean changed = this.status != status || !Objects.equals(this.tableCode, tableCode)
                || this.total.compareTo(total) != 0 || this.tax.compareTo(tax) != 0
                || !Objects.equals(this.vatLines, vatLines) || this.itemCount != itemCount
                || !Objects.equals(this.fiscalNumber, fiscalNumber);
        if (!changed) {
            return false;
        }
        if (this.status != Status.OPEN && status == Status.OPEN) {
            throw new IllegalStateException("e-Adisyon %s is %s and cannot open again".formatted(ettn, this.status));
        }
        this.status = status;
        this.tableCode = tableCode;
        this.total = total;
        this.tax = tax;
        this.vatLines = vatLines;
        this.itemCount = itemCount;
        this.fiscalNumber = fiscalNumber;
        this.closedAt = closedAt;
        this.version += 1;
        this.delivery = Delivery.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.updatedAt = now;
        return true;
    }

    /** The integrator accepted {@code sent}; a newer version made meanwhile still waits. */
    void sent(int sent, String provider, @Nullable String documentNumber, Instant now) {
        this.sentVersion = Math.max(sentVersion, sent);
        this.provider = provider;
        if (documentNumber != null) {
            this.documentNumber = documentNumber;
        }
        this.lastError = null;
        this.updatedAt = now;
        if (sentVersion >= version) {
            this.delivery = Delivery.SENT;
            this.nextAttemptAt = null;
        }
    }

    /** Tried again after 1, 2, 4… minutes, up to an hour; after the last attempt only a person retries it. */
    void failed(String error, Instant now) {
        this.delivery = Delivery.FAILED;
        this.attempts += 1;
        this.lastError = error.length() > 255 ? error.substring(0, 255) : error;
        this.updatedAt = now;
        this.nextAttemptAt = attempts >= MAX_ATTEMPTS ? null
                : now.plus(Duration.ofMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6))));
    }

    void retryNow(Instant now) {
        if (delivery != Delivery.SENT) {
            this.delivery = Delivery.PENDING;
            this.attempts = 0;
            this.nextAttemptAt = now;
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }

    public Long getBillId() {
        return billId;
    }

    public String getEttn() {
        return ettn;
    }

    public @Nullable String getTableCode() {
        return tableCode;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public @Nullable String getVatLines() {
        return vatLines;
    }

    public int getItemCount() {
        return itemCount;
    }

    public @Nullable String getFiscalNumber() {
        return fiscalNumber;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public @Nullable Instant getClosedAt() {
        return closedAt;
    }

    public int getVersion() {
        return version;
    }

    public int getSentVersion() {
        return sentVersion;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public @Nullable String getProvider() {
        return provider;
    }

    public @Nullable String getDocumentNumber() {
        return documentNumber;
    }

    public int getAttempts() {
        return attempts;
    }

    public @Nullable String getLastError() {
        return lastError;
    }

    public @Nullable Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
