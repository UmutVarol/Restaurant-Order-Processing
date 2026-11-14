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

    public enum Plan { STARTER, BUSINESS, CHAIN }

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
}
