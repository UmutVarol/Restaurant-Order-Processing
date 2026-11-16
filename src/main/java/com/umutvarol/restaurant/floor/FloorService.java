package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.floor.TableStatusResponse.OpenOrder;
import com.umutvarol.restaurant.floor.TableStatusResponse.Party;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the live floor plan: which tables are occupied, what is open on them, and who is seated or booked. */
@Service
@Transactional(readOnly = true)
public class FloorService {

    private final DiningTableRepository tables;
    private final OrderRepository orders;
    private final ObjectProvider<TableBookings> bookings;
    private final ObjectProvider<TableCalls> calls;

    public FloorService(DiningTableRepository tables, OrderRepository orders, ObjectProvider<TableBookings> bookings,
                        ObjectProvider<TableCalls> calls) {
        this.tables = tables;
        this.orders = orders;
        this.bookings = bookings;
        this.calls = calls;
    }

    private Map<Long, Instant> waiting() {
        return calls.getIfAvailable(() -> TableCalls.NONE).waitingSince();
    }

    private TableBookings bookings() {
        return bookings.getIfAvailable(() -> TableBookings.NONE);
    }

    public List<TableStatusResponse> floorPlan() {
        Map<Long, List<Order>> openByTable = orders.findByStatusInOrderByCreatedAtAsc(OrderStatus.activeStatuses())
                .stream()
                .filter(order -> order.getType() == OrderType.DINE_IN && order.getTable() != null)
                .collect(Collectors.groupingBy(order -> order.getTable().getId()));

        Map<Long, Party> seated = bookings().seated();
        Map<Long, Party> next = bookings().nextBookings();
        Map<Long, Instant> waiting = waiting();
        return tables.findByActiveTrueOrderByIdAsc().stream()
                .map(table -> toResponse(table, openByTable.getOrDefault(table.getId(), List.of()),
                        seated.get(table.getId()), next.get(table.getId()), waiting.get(table.getId())))
                .toList();
    }

    /** One table as the floor plan shows it. */
    public TableStatusResponse table(DiningTable table) {
        List<Order> open = orders.findByStatusInOrderByCreatedAtAsc(OrderStatus.activeStatuses()).stream()
                .filter(order -> order.getType() == OrderType.DINE_IN && order.getTable() != null
                        && order.getTable().getId().equals(table.getId()))
                .toList();
        return toResponse(table, open, bookings().seated().get(table.getId()),
                bookings().nextBookings().get(table.getId()), waiting().get(table.getId()));
    }

    private static TableStatusResponse toResponse(DiningTable table, List<Order> open, @Nullable Party seated,
                                                  @Nullable Party next, @Nullable Instant called) {
        BigDecimal openTotal = open.stream()
                .map(order -> order.getPricing().total())
                .reduce(Money.ZERO, BigDecimal::add);
        return new TableStatusResponse(
                table.getId(),
                table.getCode(),
                table.getSeats(),
                table.getZone(),
                !open.isEmpty(),
                open.isEmpty() ? null : open.getFirst().getCreatedAt(),
                openTotal,
                open.stream()
                        .map(order -> new OpenOrder(order.getId(), order.getStatus(), order.getCustomerName(),
                                order.itemCount(), order.getPricing().total(), order.getCreatedAt()))
                        .toList(),
                table.getQrIssuedAt(),
                open.isEmpty() ? table.getNeedsClearingSince() : null,
                seated,
                next,
                attentionSince(open, called));
    }

    /** An open call wins; otherwise the latest change to any of the table's orders. Null for an empty table. */
    static @Nullable Instant attentionSince(List<Order> open, @Nullable Instant called) {
        if (open.isEmpty()) {
            return null;
        }
        if (called != null) {
            return called;
        }
        return open.stream().map(Order::statusSince).max(Instant::compareTo).orElse(open.getFirst().getCreatedAt());
    }
}
