package com.umutvarol.restaurant.printing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One ticket for one printer. Queued in the database, so a ticket survives a printer that is switched off or out of
 * paper: it is retried with growing pauses and then marked failed for staff to reprint.
 */
@Entity
@Table(name = "print_jobs")
public class PrintJob {

    /** The first ticket, a reprint, a test page, a course fired later, or a void slip. */
    public enum Kind { TICKET, REPRINT, TEST, FIRE, VOID }

    public enum Status { QUEUED, PRINTED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "printer_id")
    private Printer printer;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "requested_by", length = 50)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "printed_at")
    private Instant printedAt;

    private Integer course;

    @Column(name = "line_id")
    private Long lineId;

    private Integer units;

    protected PrintJob() {
        // for JPA
    }

    PrintJob(Printer printer, Kind kind, @Nullable Long orderId, @Nullable String requestedBy, Instant at) {
        this.printer = printer;
        this.kind = kind;
        this.orderId = orderId;
        this.requestedBy = requestedBy;
        this.status = Status.QUEUED;
        this.createdAt = at;
        this.nextAttemptAt = at;
    }

    /** A fired course: only its dishes print. */
    PrintJob forCourse(int course) {
        this.course = course;
        return this;
    }

    /** A void slip: these units of this dish are off. */
    PrintJob forVoid(Long lineId, int units) {
        this.lineId = lineId;
        this.units = units;
        return this;
    }

    public @Nullable Integer getCourse() {
        return course;
    }

    public @Nullable Long getLineId() {
        return lineId;
    }

    public @Nullable Integer getUnits() {
        return units;
    }

    void printed(Instant at) {
        this.status = Status.PRINTED;
        this.printedAt = at;
        this.attempts++;
        this.lastError = null;
        printer.printed(at);
    }

    /** @return whether the job gave up */
    boolean failed(String error, Instant at, int maxAttempts) {
        this.attempts++;
        this.lastError = error.length() > 255 ? error.substring(0, 255) : error;
        printer.failed(error);
        if (attempts >= maxAttempts) {
            this.status = Status.FAILED;
            return true;
        }
        // 5 s, 10 s, 20 s, 40 s …: a printer that is just out of paper catches up without hammering it
        this.nextAttemptAt = at.plus(Duration.ofSeconds(5L << Math.min(attempts - 1, 6)));
        return false;
    }

    public Long getId() {
        return id;
    }

    public Printer getPrinter() {
        return printer;
    }

    public @Nullable Long getOrderId() {
        return orderId;
    }

    public Kind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public @Nullable String getLastError() {
        return lastError;
    }

    public @Nullable String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getPrintedAt() {
        return printedAt;
    }
}
