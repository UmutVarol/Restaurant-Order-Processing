package com.umutvarol.restaurant.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.umutvarol.restaurant.business.BranchScoped;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Published whenever an order is created, changes status or is otherwise changed (adjusted, moved). Listeners (live kitchen display, metrics) react
 * after the surrounding transaction commits, so they never observe data that is later rolled back.
 */
public record OrderChangedEvent(
        Kind kind,
        Long orderId,
        OrderType orderType,
        OrderStatus status,
        @Nullable OrderStatus previousStatus,
        @Nullable String tableCode,
        String customerName,
        Instant occurredAt,
        @JsonIgnore @Nullable Long branchId) implements BranchScoped {

    public enum Kind {
        CREATED,
        STATUS_CHANGED,
        /** Lines, prices or the table changed; the status did not. */
        UPDATED
    }

    static OrderChangedEvent created(Order order) {
        return new OrderChangedEvent(Kind.CREATED, order.getId(), order.getType(), order.getStatus(), null,
                tableCode(order), order.getCustomerName(), order.getCreatedAt(), order.getBranchId());
    }

    static OrderChangedEvent statusChanged(Order order, OrderStatus previous) {
        return new OrderChangedEvent(Kind.STATUS_CHANGED, order.getId(), order.getType(), order.getStatus(), previous,
                tableCode(order), order.getCustomerName(), order.getUpdatedAt(), order.getBranchId());
    }

    public static OrderChangedEvent updated(Order order) {
        return new OrderChangedEvent(Kind.UPDATED, order.getId(), order.getType(), order.getStatus(), null,
                tableCode(order), order.getCustomerName(), order.getUpdatedAt(), order.getBranchId());
    }

    private static @Nullable String tableCode(Order order) {
        return order.getTable() == null ? null : order.getTable().getCode();
    }
}
