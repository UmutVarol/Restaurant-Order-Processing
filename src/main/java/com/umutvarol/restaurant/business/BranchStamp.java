package com.umutvarol.restaurant.business;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Stamps the branch on everything that happens in a place, when it is first saved. Hibernate gets its instance from
 * Spring (Boot wires Hibernate to Spring's bean container), so each application context has its own.
 * <p>
 * Also keeps a request that works in one branch from reading another branch's records by id.
 */
public class BranchStamp {

    private final ObjectProvider<BranchRepository> branches;

    public BranchStamp(ObjectProvider<BranchRepository> branches) {
        this.branches = branches;
    }

    @PrePersist
    void stamp(Object entity) {
        if (!(entity instanceof BranchOwned owned) || owned.getBranchId() != null) {
            return;
        }
        Long branch = Optional.ofNullable(owned.branchHint()).orElse(BranchContext.current());
        if (branch == null) {
            branch = branches.getObject().findFirstByActiveTrueOrderByIdAsc()
                    .map(Branch::getId)
                    .orElseThrow(() -> new IllegalStateException("This business has no branch"));
        }
        owned.assignBranch(branch);
    }

    /**
     * Queries only see the branch a request works in (the branch filter); this covers what is fetched by id, which
     * filters do not reach: from another branch it is as if it did not exist.
     */
    @PostLoad
    void guard(Object entity) {
        Long branch = BranchContext.current();
        if (branch != null && entity instanceof BranchOwned owned && owned.getBranchId() != null
                && !branch.equals(owned.getBranchId())) {
            throw new ResourceNotFoundException(entity.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2"),
                    owned.getId());
        }
    }
}
