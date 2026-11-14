package com.umutvarol.restaurant.business;

import org.jspecify.annotations.Nullable;

/** A live event that happened in one branch: only that branch's screens (and those watching all branches) get it. */
public interface BranchScoped {

    /** The branch it happened in; none when it concerns the whole business. */
    @Nullable Long branchId();
}
