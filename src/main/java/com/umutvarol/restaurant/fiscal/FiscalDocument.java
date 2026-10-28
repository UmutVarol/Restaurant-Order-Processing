package com.umutvarol.restaurant.fiscal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;

/**
 * The legal receipt or invoice of one bill. Once issued it is a record of what was declared to the state and never
 * changes; until then it is retried with growing pauses and stays visible to the cashier.
 */
@Entity
@Table(name = "fiscal_documents")
public class FiscalDocument {

    /** Where the document is. */
    public enum Status {
        /** A guest asked for an invoice; it is issued when the bill is paid. */
        WAITING,
        /** To be issued (again). */
        PENDING,
        ISSUED,
        /** The device or integrator refused or did not answer; it will be tried again. */
        FAILED
    }

    static final int MAX_ATTEMPTS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bill_id", nullable = false, unique = true)
    private Long billId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FiscalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 20)
    private @Nullable String provider;

    @Column(length = 80)
    private @Nullable String reference;

    @Column(name = "document_number", length = 40)
    private @Nullable String documentNumber;

    @Column(name = "pdf_url", length = 255)
    private @Nullable String pdfUrl;

    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal total;

    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal tax;

    @Column(name = "buyer_name", length = 120)
    private @Nullable String buyerName;

    @Column(name = "buyer_tax_id", length = 11)
    private @Nullable String buyerTaxId;

    @Column(name = "buyer_address", length = 255)
    private @Nullable String buyerAddress;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 255)
    private @Nullable String lastError;

    @Column(name = "next_attempt_at")
    private @Nullable Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private @Nullable Instant issuedAt;

    @Version
    private long version;

    protected FiscalDocument() {
        // for JPA
    }

    private FiscalDocument(Long billId, FiscalKind kind, Status status, Instant at) {
        this.billId = billId;
        this.kind = kind;
        this.status = status;
        this.createdAt = at;
        this.nextAttemptAt = at;
    }

    static FiscalDocument forBill(Long billId, FiscalKind kind, Instant at) {
        return new FiscalDocument(billId, kind, Status.PENDING, at);
    }

    static FiscalDocument invoiceRequested(Long billId, String name, String taxId, @Nullable String address, Instant at) {
        FiscalDocument document = new FiscalDocument(billId, FiscalKind.E_ARSIV, Status.WAITING, at);
        document.buyer(name, taxId, address);
        return document;
    }

    void buyer(String name, String taxId, @Nullable String address) {
        requireNotIssued();
        this.kind = FiscalKind.E_ARSIV;
        this.buyerName = name;
        this.buyerTaxId = taxId;
        this.buyerAddress = address;
    }

    /** The bill is paid: issue now. */
    void due(BigDecimal total, BigDecimal tax, Instant at) {
        requireNotIssued();
        this.status = Status.PENDING;
        this.total = total;
        this.tax = tax;
        this.nextAttemptAt = at;
    }

    void issued(String provider, String reference, String number, @Nullable String pdfUrl, Instant at) {
        requireNotIssued();
        this.status = Status.ISSUED;
        this.provider = provider;
        this.reference = reference;
        this.documentNumber = number;
        this.pdfUrl = pdfUrl;
        this.issuedAt = at;
        this.attempts += 1;
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    /** Tried again after 1, 2, 4… minutes, up to an hour; after the last attempt only a person retries it. */
    void failed(String error, Instant at) {
        requireNotIssued();
        this.status = Status.FAILED;
        this.attempts += 1;
        this.lastError = error.length() > 255 ? error.substring(0, 255) : error;
        this.nextAttemptAt = attempts >= MAX_ATTEMPTS ? null
                : at.plus(Duration.ofMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6))));
    }

    void retryNow(Instant at) {
        requireNotIssued();
        this.status = Status.PENDING;
        this.nextAttemptAt = at;
    }

    private void requireNotIssued() {
        if (status == Status.ISSUED) {
            throw new IllegalStateException("Fiscal document %d is issued and cannot change".formatted(id));
        }
    }

    public Long getId() {
        return id;
    }

    public Long getBillId() {
        return billId;
    }

    public FiscalKind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public @Nullable String getProvider() {
        return provider;
    }

    public @Nullable String getReference() {
        return reference;
    }

    public @Nullable String getDocumentNumber() {
        return documentNumber;
    }

    public @Nullable String getPdfUrl() {
        return pdfUrl;
    }

    public @Nullable BigDecimal getTotal() {
        return total;
    }

    public @Nullable BigDecimal getTax() {
        return tax;
    }

    public @Nullable String getBuyerName() {
        return buyerName;
    }

    public @Nullable String getBuyerTaxId() {
        return buyerTaxId;
    }

    public @Nullable String getBuyerAddress() {
        return buyerAddress;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getIssuedAt() {
        return issuedAt;
    }
}
