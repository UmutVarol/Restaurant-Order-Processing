package com.umutvarol.restaurant.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.TenantId;

/**
 * One place of a business: a restaurant of a chain, or the only one. Tables, orders, bills, printers, tills, shifts
 * and reservations belong to a branch; the menu, staff and stock to the business. The "branch" filter declared here
 * limits lists to the branch a request works in (BranchTransactionManager turns it on).
 */
@Entity
@Table(name = "branches")
@FilterDef(name = Branch.FILTER, parameters = @ParamDef(name = "branch", type = Long.class), defaultCondition = "branch_id = :branch")
public class Branch {

    public static final String FILTER = "branch";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Where the branch is, for the guest app's "you are here" (round three); empty until a manager sets it. */
    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(length = 200)
    private String address;

    /** Whether guests can find it in the directory. Off until the restaurant turns it on. */
    @Column(nullable = false)
    private boolean listed;

    protected Branch() {
        // for JPA
    }

    public Branch(String code, String name, Instant now) {
        this.code = code;
        this.name = name;
        this.createdAt = now;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** The directory entry: position (both or neither), address, and whether it is listed (needs a position). */
    public void placeInDirectory(@org.jspecify.annotations.Nullable Double latitude, @org.jspecify.annotations.Nullable Double longitude,
                                 @org.jspecify.annotations.Nullable String address, boolean listed) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.listed = listed && latitude != null && longitude != null;
    }

    public @org.jspecify.annotations.Nullable Double getLatitude() {
        return latitude;
    }

    public @org.jspecify.annotations.Nullable Double getLongitude() {
        return longitude;
    }

    public @org.jspecify.annotations.Nullable String getAddress() {
        return address;
    }

    public boolean isListed() {
        return listed;
    }

    public Long getId() {
        return id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
