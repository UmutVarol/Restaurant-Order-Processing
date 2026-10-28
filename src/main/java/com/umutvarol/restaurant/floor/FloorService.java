package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.floor.TableStatusResponse.OpenOrder;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the live floor plan: which tables are occupied and what is open on them. */
@Service
@Transactional(readOnly = true)
public class FloorService {

    private final DiningTableRepository tables;
    private final OrderRepository orders;

    public FloorService(DiningTableRepository tables, OrderRepository orders) {
        this.tables = tables;
        this.orders = orders;
    }

    public List<TableStatusResponse> floorPlan() {
        Map<Long, List<Order>> openByTable = orders.findByStatusInOrderByCreatedAtAsc(OrderStatus.activeStatuses())
                .stream()
                .filter(order -> order.getType() == OrderType.DINE_IN && order.getTable() != null)
                .collect(Collectors.groupingBy(order -> order.getTable().getId()));

        return tables.findAllByOrderByIdAsc().stream()
                .map(table -> toResponse(table, openByTable.getOrDefault(table.getId(), List.of())))
                .toList();
    }

    private static TableStatusResponse toResponse(DiningTable table, List<Order> open) {
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
                table.getQrIssuedAt());
    }
}
