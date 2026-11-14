package com.umutvarol.restaurant.business;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The branch a piece of work is for. Most work needs no thought (queries see the request's branch, new records are
 * stamped); this is for what is kept once per branch and day, such as a day's closing or its tip pool, and for
 * checks that a name is free within a branch.
 */
@Component
public class WorkingBranch {

    private final BranchRepository branches;

    WorkingBranch(BranchRepository branches) {
        this.branches = branches;
    }

    /** Where something new lands: the branch the request works in, else the business's first open branch. */
    public long forNew() {
        Long current = BranchContext.current();
        if (current != null) {
            return current;
        }
        return branches.findFirstByActiveTrueOrderByIdAsc().map(Branch::getId)
                .orElseThrow(() -> new IllegalStateException("This business has no branch"));
    }

    /**
     * For what is kept per branch: the branch the request works in. A business with a single open branch needs no choice;
     * someone working in all branches of a chain picks one first.
     */
    public long require() {
        Long current = BranchContext.current();
        if (current != null) {
            return current;
        }
        List<Branch> open = branches.findAllByOrderByIdAsc().stream().filter(Branch::isActive).toList();
        if (open.size() == 1) {
            return open.getFirst().getId();
        }
        throw new BusinessRuleException("BRANCH_REQUIRED", "Choose a branch first");
    }
}
