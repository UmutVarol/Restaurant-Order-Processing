package com.umutvarol.restaurant.business;

import org.jspecify.annotations.Nullable;

/**
 * Which branch the current request works in, or none: a chain owner looking at all branches at once. Only ever
 * narrows what the business already allows (BusinessContext stays the hard wall between businesses).
 */
public final class BranchContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private BranchContext() {
    }

    public static @Nullable Long current() {
        return CURRENT.get();
    }

    static void set(@Nullable Long branch) {
        if (branch == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(branch);
        }
    }
}
