package com.umutvarol.restaurant.business;

import org.jspecify.annotations.Nullable;

/**
 * Something that happens in a place: it belongs to one branch. Stamped on insert (BranchStamp) from, in order, the
 * thing it happens at (an order's table), the branch the request works in, or the business's first branch, which is
 * all a single restaurant ever has.
 */
public interface BranchOwned {

    Long getId();

    @Nullable Long getBranchId();

    void assignBranch(long branchId);

    /** The branch this belongs to by its nature, if any: the branch of an order's table, say. */
    default @Nullable Long branchHint() {
        return null;
    }
}
