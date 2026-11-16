package com.umutvarol.restaurant.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** One device that wants to be told of things: a browser's push address and keys, its person, branch and topics. */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** The branch of the person who subscribed; null for someone who works in all of them. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, length = 1000, unique = true)
    private String endpoint;

    @Column(length = 120)
    private String p256dh;

    @Column(length = 40)
    private String auth;

    @Column(nullable = false, length = 100)
    private String topics;

    @Column(nullable = false, length = 5)
    private String lang;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(nullable = false)
    private int failures;

    protected PushSubscription() {
        // for JPA
    }

    PushSubscription(String endpoint, Instant at) {
        this(endpoint, "WEB", at);
    }

    /** {@code kind}: WEB for a browser (Web Push), FCM for a store app (its Firebase token, as {@code fcm:<token>}). */
    PushSubscription(String endpoint, String kind, Instant at) {
        this.endpoint = endpoint;
        this.kind = kind;
        this.createdAt = at;
    }

    void set(String username, @Nullable Long branchId, @Nullable String p256dh, @Nullable String auth, Set<PushTopic> topics, String lang) {
        this.username = username;
        this.branchId = branchId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.topics = topics.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.lang = lang;
        this.failures = 0;
    }

    void delivered(Instant at) {
        this.lastSuccessAt = at;
        this.failures = 0;
    }

    /** One more failed delivery; how many in a row. */
    int failed() {
        return ++failures;
    }

    public Long getId() {
        return id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public @Nullable Long getBranchId() {
        return branchId;
    }

    public String getUsername() {
        return username;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getKind() {
        return kind;
    }

    /** A store app's Firebase token. */
    @Nullable String fcmToken() {
        return "FCM".equals(kind) && endpoint.startsWith("fcm:") ? endpoint.substring(4) : null;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public Set<PushTopic> getTopics() {
        if (topics == null || topics.isBlank()) {
            return EnumSet.noneOf(PushTopic.class);
        }
        return Arrays.stream(topics.split(",")).map(PushTopic::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(PushTopic.class)));
    }

    public String getLang() {
        return lang;
    }

    public @Nullable Instant getLastSuccessAt() {
        return lastSuccessAt;
    }
}
