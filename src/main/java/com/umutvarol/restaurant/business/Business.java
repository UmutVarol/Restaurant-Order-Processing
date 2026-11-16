package com.umutvarol.restaurant.business;

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

/** A customer of Sofra: one restaurant, or a chain of them. Everything else belongs to exactly one business. */
@Entity
@Table(name = "businesses")
public class Business {

    public enum Plan {
        STARTER, BUSINESS, CHAIN;

        /** The Starter plan is the core of Sofra; Business has every part but the Chain plan's own tools. */
        public boolean includes(Feature feature) {
            return feature == Feature.CHAIN ? this == CHAIN : this != STARTER;
        }
    }

    public enum Status { TRIAL, ACTIVE, PAST_DUE, READ_ONLY, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Its address: {@code slug.sofra.app}, and the business code typed into the app. */
    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Where the provider's receipts and Sofra's notices about the subscription go. */
    @Column(name = "owner_email", length = 120)
    private String ownerEmail;

    @Column(name = "billing_provider", length = 20)
    private String billingProvider;

    @Column(name = "billing_customer_ref", length = 100)
    private String billingCustomerRef;

    @Column(name = "billing_subscription_ref", length = 100)
    private String billingSubscriptionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", length = 10)
    private BillingInterval billingInterval;

    @Column(name = "billed_branches")
    private Integer billedBranches;

    @Column(name = "period_ends_at")
    private Instant periodEndsAt;

    /** Since when a payment is late; the business turns read-only once the grace period has passed. */
    @Column(name = "past_due_since")
    private Instant pastDueSince;

    /** When the last provider event applied happened; older ones arriving late are ignored. */
    @Column(name = "billing_event_at")
    private Instant billingEventAt;

    protected Business() {
        // for JPA
    }

    Business(String slug, String name, String timeZone, Plan plan, Status status, @Nullable Instant trialEndsAt, Instant now) {
        this.slug = slug;
        this.name = name;
        this.timeZone = timeZone;
        this.plan = plan;
        this.status = status;
        this.trialEndsAt = trialEndsAt;
        this.createdAt = now;
    }

    void rename(String name) {
        this.name = name;
    }

    public void setOwnerEmail(@Nullable String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    /**
     * What the billing provider says about the subscription. Paying (again) opens a read-only business at once;
     * a late payment starts the grace period; a cancelled subscription makes the business read-only. An event that
     * happened before the last one applied is ignored (providers may deliver them out of order); whether it applied.
     */
    public boolean subscribed(String provider, String customerRef, String subscriptionRef, Plan plan, BillingInterval interval,
                              int branches, Status status, @Nullable Instant periodEndsAt, @Nullable Instant occurredAt,
                              Instant now) {
        if (occurredAt != null && billingEventAt != null && occurredAt.isBefore(billingEventAt)) {
            return false;
        }
        if (occurredAt != null) {
            this.billingEventAt = occurredAt;
        }
        this.billingProvider = provider;
        this.billingCustomerRef = customerRef;
        this.billingSubscriptionRef = subscriptionRef;
        this.plan = plan;
        this.billingInterval = interval;
        this.billedBranches = branches;
        this.periodEndsAt = periodEndsAt;
        if (status == Status.PAST_DUE) {
            if (this.status != Status.PAST_DUE && this.status != Status.READ_ONLY) {
                this.pastDueSince = now;
                this.status = Status.PAST_DUE;
            }
            return true;
        }
        this.pastDueSince = null;
        this.status = status == Status.ACTIVE ? Status.ACTIVE : Status.CANCELLED;
        return true;
    }

    /** Ends a trial that ran out, or a grace period that passed: read-only until paid. Whether anything changed. */
    public boolean lapse(Instant now, java.time.Duration grace) {
        boolean trialOver = status == Status.TRIAL && trialEndsAt != null && !trialEndsAt.isAfter(now);
        boolean graceOver = status == Status.PAST_DUE && pastDueSince != null && !pastDueSince.plus(grace).isAfter(now);
        if (trialOver || graceOver) {
            status = Status.READ_ONLY;
            return true;
        }
        return false;
    }

    /** Whether the business may change anything now; reading, exporting and paying always work. */
    public boolean writable(Instant now) {
        return switch (status) {
            case ACTIVE, PAST_DUE -> true;
            case TRIAL -> trialEndsAt == null || trialEndsAt.isAfter(now);
            case READ_ONLY, CANCELLED -> false;
        };
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Plan getPlan() {
        return plan;
    }

    public Status getStatus() {
        return status;
    }

    public @Nullable Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable String getOwnerEmail() {
        return ownerEmail;
    }

    public @Nullable String getBillingProvider() {
        return billingProvider;
    }

    public @Nullable String getBillingCustomerRef() {
        return billingCustomerRef;
    }

    public @Nullable String getBillingSubscriptionRef() {
        return billingSubscriptionRef;
    }

    public @Nullable BillingInterval getBillingInterval() {
        return billingInterval;
    }

    public @Nullable Integer getBilledBranches() {
        return billedBranches;
    }

    public @Nullable Instant getPeriodEndsAt() {
        return periodEndsAt;
    }

    public @Nullable Instant getPastDueSince() {
        return pastDueSince;
    }
}
