package com.umutvarol.restaurant.order;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle of an order, modelled as a small state machine whose path depends on the {@link OrderType}:
 *
 * <pre>
 *                                   ┌─▶ SERVED ───────────▶ COMPLETED   dine-in  (bill paid)
 * PENDING ─▶ PREPARING ─▶ READY ────┼─────────────────────▶ COMPLETED   takeaway (picked up)
 *    │           │                  └─▶ OUT_FOR_DELIVERY ─▶ COMPLETED   delivery (delivered)
 *    └───────────┴─▶ CANCELLED
 * </pre>
 */
public enum OrderStatus {
    PENDING,
    PREPARING,
    READY,
    SERVED,
    OUT_FOR_DELIVERY,
    COMPLETED,
    CANCELLED;

    private static final Set<OrderStatus> ACTIVE = EnumSet.of(PENDING, PREPARING, READY, SERVED, OUT_FOR_DELIVERY);

    /** The status that follows this one for an order of the given type, if any. */
    public Optional<OrderStatus> next(OrderType type) {
        return switch (this) {
            case PENDING -> Optional.of(PREPARING);
            case PREPARING -> Optional.of(READY);
            case READY -> Optional.of(switch (type) {
                case DINE_IN -> SERVED;
                case DELIVERY -> OUT_FOR_DELIVERY;
                case TAKEAWAY -> COMPLETED;
            });
            case SERVED, OUT_FOR_DELIVERY -> Optional.of(COMPLETED);
            case COMPLETED, CANCELLED -> Optional.empty();
        };
    }

    /** Orders can be cancelled until the kitchen has finished cooking them. */
    public boolean isCancellable() {
        return this == PENDING || this == PREPARING;
    }

    /** Whether the order is still in progress (not completed or cancelled). */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public static Set<OrderStatus> activeStatuses() {
        return EnumSet.copyOf(ACTIVE);
    }
}
