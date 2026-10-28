package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.table.DiningTable;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Something a guest asked for from their table: an order to accept, a waiter, or the bill. Guests never place
 * orders directly; a waiter turns an accepted request into a real order, so a photographed QR code cannot flood
 * the kitchen.
 */
@Entity
@Table(name = "guest_requests")
public class GuestRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_id")
    private DiningTable table;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GuestRequestKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GuestRequestStatus status;

    @Column(name = "guest_name", length = 40)
    private String guestName;

    @Column(length = 140)
    private String note;

    @Column(name = "quoted_total", precision = 12, scale = 2)
    private BigDecimal quotedTotal;

    @Column(name = "order_id")
    private Long orderId;

    @Column(length = 140)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "handled_by", length = 50)
    private String handledBy;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<GuestRequestLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected GuestRequest() {
        // for JPA
    }

    private GuestRequest(DiningTable table, GuestRequestKind kind, @Nullable String guestName, @Nullable String note,
                         Instant at) {
        this.publicId = UUID.randomUUID().toString();
        this.table = table;
        this.kind = kind;
        this.status = GuestRequestStatus.OPEN;
        this.guestName = guestName;
        this.note = note;
        this.createdAt = at;
    }

    static GuestRequest order(DiningTable table, @Nullable String guestName, @Nullable String note, Instant at) {
        return new GuestRequest(table, GuestRequestKind.ORDER, guestName, note, at);
    }

    static GuestRequest call(DiningTable table, GuestRequestKind kind, Instant at) {
        if (kind == GuestRequestKind.ORDER) {
            throw new IllegalArgumentException("Use order() for an order request");
        }
        return new GuestRequest(table, kind, null, null, at);
    }

    void addLine(GuestRequestLine line) {
        lines.add(line);
    }

    void priced(BigDecimal total) {
        this.quotedTotal = total;
    }

    void accept(Long orderId, Instant at, String by) {
        requireOpen(GuestRequestKind.ORDER);
        this.status = GuestRequestStatus.ACCEPTED;
        this.orderId = orderId;
        handled(at, by);
    }

    void reject(String reason, Instant at, String by) {
        requireOpen(GuestRequestKind.ORDER);
        this.status = GuestRequestStatus.REJECTED;
        this.reason = reason;
        handled(at, by);
    }

    void done(Instant at, String by) {
        requireOpen(null);
        if (kind == GuestRequestKind.ORDER) {
            throw new InvalidStateException("GUEST_REQUEST_NOT_A_CALL", "Accept or reject an order request",
                    Map.of("id", String.valueOf(id)));
        }
        this.status = GuestRequestStatus.DONE;
        handled(at, by);
    }

    private void handled(Instant at, String by) {
        this.handledAt = at;
        this.handledBy = by;
    }

    private void requireOpen(@Nullable GuestRequestKind expected) {
        if (status != GuestRequestStatus.OPEN || expected != null && kind != expected) {
            throw new InvalidStateException("GUEST_REQUEST_HANDLED", "This request has already been dealt with",
                    Map.of("id", String.valueOf(id), "status", status));
        }
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public DiningTable getTable() {
        return table;
    }

    public GuestRequestKind getKind() {
        return kind;
    }

    public GuestRequestStatus getStatus() {
        return status;
    }

    public @Nullable String getGuestName() {
        return guestName;
    }

    public @Nullable String getNote() {
        return note;
    }

    public @Nullable BigDecimal getQuotedTotal() {
        return quotedTotal;
    }

    public @Nullable Long getOrderId() {
        return orderId;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getHandledAt() {
        return handledAt;
    }

    public @Nullable String getHandledBy() {
        return handledBy;
    }

    public List<GuestRequestLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
