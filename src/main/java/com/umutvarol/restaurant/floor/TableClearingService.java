package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Which tables wait to be cleared. When the last order at a table completes (its bill is paid) the table needs
 * clearing; a waiter marks it done from the service screen, or it clears by itself when new guests order there.
 */
@Service
@Transactional
public class TableClearingService {

    private static final Logger audit = LoggerFactory.getLogger("order.audit");

    private final DiningTableRepository tables;
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TableClearingService(DiningTableRepository tables, OrderRepository orders, ApplicationEventPublisher events,
                                Clock clock) {
        this.tables = tables;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    /** In the same transaction as the order change, so the floor plan never shows one without the other. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderChanged(OrderChangedEvent event) {
        if (event.orderType() != OrderType.DINE_IN || event.tableCode() == null) {
            return;
        }
        Order order = orders.findById(event.orderId()).orElse(null);
        DiningTable table = order == null ? null : order.getTable();
        if (table == null) {
            return;
        }
        if (event.kind() == OrderChangedEvent.Kind.CREATED) {
            table.cleared();
        } else if (event.status() == OrderStatus.COMPLETED
                && !orders.existsByTableIdAndStatusIn(table.getId(), OrderStatus.activeStatuses())) {
            table.needsClearing(event.occurredAt());
        }
    }

    /** A waiter cleared and laid the table. */
    public TableStatusResponse cleared(Long tableId, String actor, FloorService floor) {
        DiningTable table = tables.findById(tableId).orElseThrow(() -> new ResourceNotFoundException("table", tableId));
        Instant now = clock.instant();
        if (table.cleared()) {
            audit.info("table.cleared table={} by={}", table.getCode(), actor);
            events.publishEvent(new TableChangedEvent(table.getId(), table.getCode(), null, now));
        }
        return floor.table(table);
    }
}
