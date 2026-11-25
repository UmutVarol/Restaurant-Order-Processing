package com.umutvarol.restaurant.planning;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.BranchStamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;

/** A dish the kitchen marked as prepared for a day. */
@Entity
@Table(name = "prep_checks")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class PrepCheck implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "prep_date", nullable = false, updatable = false)
    private LocalDate prepDate;

    @Column(name = "menu_item_id", nullable = false, updatable = false)
    private Long menuItemId;

    @Column(name = "done_at", nullable = false)
    private Instant doneAt;

    @Column(name = "done_by", nullable = false, length = 50)
    private String doneBy;

    protected PrepCheck() {
        // for JPA
    }

    PrepCheck(LocalDate prepDate, Long menuItemId, Instant doneAt, String doneBy) {
        this.prepDate = prepDate;
        this.menuItemId = menuItemId;
        this.doneAt = doneAt;
        this.doneBy = doneBy;
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

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getDoneBy() {
        return doneBy;
    }

    public Instant getDoneAt() {
        return doneAt;
    }
}
