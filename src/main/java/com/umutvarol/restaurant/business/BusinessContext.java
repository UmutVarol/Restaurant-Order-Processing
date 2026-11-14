package com.umutvarol.restaurant.business;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Which business the current thread works for. Set once per request (BusinessFilter), per business in background
 * jobs (BusinessScope), and read by Hibernate on every query (BusinessTenantResolver). {@link #ALL} is the one
 * deliberate exception: a lookup across businesses, used only to find which business a guest's token belongs to and
 * for system-wide monitoring.
 */
public final class BusinessContext {

    /** Hibernate's root tenant: sees every business. Never set for ordinary work. */
    public static final long ALL = 0L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private BusinessContext() {
    }

    public static @Nullable Long current() {
        return CURRENT.get();
    }

    public static <T> T call(long businessId, Supplier<T> work) {
        Long previous = CURRENT.get();
        CURRENT.set(businessId);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void run(long businessId, Runnable work) {
        call(businessId, () -> {
            work.run();
            return null;
        });
    }

    /** Across all businesses: for finding a token's business and for monitoring, nothing else. */
    public static <T> T acrossAll(Supplier<T> work) {
        return call(ALL, work);
    }

    static void set(long businessId) {
        CURRENT.set(businessId);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(@Nullable Long previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
