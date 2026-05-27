package com.swe312.restaurant.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Order {
    private final int orderId;
    private final String customerName;
    private final String tableInfo;
    private final OrderType orderType;
    private final LocalDateTime createdAt;
    private final List<OrderItem> items;
    private OrderStatus status;

    public Order(int orderId, String customerName, String tableInfo, OrderType orderType, List<OrderItem> items) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.tableInfo = tableInfo;
        this.orderType = orderType;
        this.createdAt = LocalDateTime.now();
        this.items = new ArrayList<>(items);
        this.status = OrderStatus.PENDING;
    }

    public int getOrderId() {
        return orderId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getTableInfo() {
        return tableInfo;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public List<OrderItem> getItems() {
        return new ArrayList<>(items); // Defansif kopyalama (Encapsulation)
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public double getSubtotal() {
        double subtotal = 0.0;
        for (OrderItem item : items) {
            subtotal += item.getLineTotal();
        }
        return subtotal;
    }

    public double getTotal() {
        return getSubtotal() * 1.10;
    }

    @Override
    public String toString() {
        return "Order #" + orderId + " | " + customerName + " | " + status.getDisplayName();
    }
}