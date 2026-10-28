package com.umutvarol.restaurant.channel;

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
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An order a platform sent, kept once per platform order id, with what became of it. */
@Entity
@Table(name = "channel_orders")
public class ChannelOrder {

    /** What became of it. */
    public enum Status {
        /** Cannot be placed safely yet: an unmapped item, a sold-out dish. A manager decides. */
        ATTENTION,
        /** On the kitchen display and printers like any other order. */
        PLACED,
        /** Turned down; the platform was told. */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(name = "external_id", nullable = false, length = 80)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Column(name = "order_id")
    private @Nullable Long orderId;

    /** The order as the platform sent it (normalised JSON), so it can be placed later once it is mapped. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "platform_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformTotal;

    @Column(name = "our_total", precision = 12, scale = 2)
    private @Nullable BigDecimal ourTotal;

    /** The platform's prices do not match our menu's for the mapped dishes. */
    @Column(name = "price_differs", nullable = false)
    private boolean priceDiffers;

    @Column(name = "problem_code", length = 40)
    private @Nullable String problemCode;

    @Column(length = 255)
    private @Nullable String problem;

    @Column(name = "synced_status", length = 20)
    private @Nullable String syncedStatus;

    @Column(name = "sync_error", length = 255)
    private @Nullable String syncError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ChannelOrder() {
        // for JPA
    }

    ChannelOrder(Channel channel, String externalId, String payload, BigDecimal platformTotal, Instant at) {
        this.channel = channel;
        this.externalId = externalId;
        this.payload = payload;
        this.platformTotal = platformTotal;
        this.status = Status.ATTENTION;
        this.problemCode = "RECEIVED";
        this.receivedAt = at;
        this.updatedAt = at;
    }

    void placed(Long orderId, BigDecimal ourTotal, boolean priceDiffers, Instant at) {
        this.status = Status.PLACED;
        this.orderId = orderId;
        this.ourTotal = ourTotal;
        this.priceDiffers = priceDiffers;
        this.problemCode = null;
        this.problem = null;
        this.updatedAt = at;
    }

    void needsAttention(String code, String problem, Instant at) {
        this.status = Status.ATTENTION;
        this.problemCode = code;
        this.problem = problem.length() > 255 ? problem.substring(0, 255) : problem;
        this.updatedAt = at;
    }

    void rejected(Instant at) {
        this.status = Status.REJECTED;
        this.updatedAt = at;
    }

    void synced(String status, @Nullable String error, Instant at) {
        this.syncedStatus = status;
        this.syncError = error;
        this.updatedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public Status getStatus() {
        return status;
    }

    public @Nullable Long getOrderId() {
        return orderId;
    }

    public String getPayload() {
        return payload;
    }

    public BigDecimal getPlatformTotal() {
        return platformTotal;
    }

    public @Nullable BigDecimal getOurTotal() {
        return ourTotal;
    }

    public boolean isPriceDiffers() {
        return priceDiffers;
    }

    public @Nullable String getProblemCode() {
        return problemCode;
    }

    public @Nullable String getProblem() {
        return problem;
    }

    public @Nullable String getSyncedStatus() {
        return syncedStatus;
    }

    public @Nullable String getSyncError() {
        return syncError;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
