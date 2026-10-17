package com.umutvarol.restaurant.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Business metrics exposed through Micrometer ({@code /actuator/prometheus}):
 * <ul>
 *     <li>{@code sofra.orders.placed{type}} — orders placed, by fulfilment type</li>
 *     <li>{@code sofra.orders.transitions{status}} — status changes, by target status</li>
 *     <li>{@code sofra.orders.active} — orders currently in progress</li>
 * </ul>
 */
@Component
class OrderMetrics {

    private final MeterRegistry registry;

    OrderMetrics(MeterRegistry registry, OrderRepository orders) {
        this.registry = registry;
        Gauge.builder("sofra.orders.active", orders, repo -> repo.countByStatusIn(OrderStatus.activeStatuses()))
                .description("Orders that are not yet completed or cancelled")
                .register(registry);
    }

    @TransactionalEventListener
    void on(OrderChangedEvent event) {
        switch (event.kind()) {
            case CREATED -> Counter.builder("sofra.orders.placed")
                    .description("Orders placed")
                    .tag("type", event.orderType().name())
                    .register(registry)
                    .increment();
            case STATUS_CHANGED -> Counter.builder("sofra.orders.transitions")
                    .description("Order status transitions")
                    .tag("status", event.status().name())
                    .register(registry)
                    .increment();
        }
    }
}
