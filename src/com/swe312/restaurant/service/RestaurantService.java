package com.swe312.restaurant.service;

import com.swe312.restaurant.model.*;
import java.util.ArrayList;
import java.util.List;

public class RestaurantService {
    // Tasarım Örüntüsü: Singleton Pattern (Double-Checked Locking)
    private static volatile RestaurantService instance;

    private final List<MenuItem> menuItems;
    private final List<Order> orders;
    private int nextOrderId;

    // Constructor private yapıldı ki dışarıdan new ile çağırılamasın.
    private RestaurantService() {
        menuItems = buildDefaultMenu();
        orders = new ArrayList<>();
        nextOrderId = 1001;
    }

    public static RestaurantService getInstance() {
        if (instance == null) {
            synchronized (RestaurantService.class) {
                if (instance == null) {
                    instance = new RestaurantService();
                }
            }
        }
        return instance;
    }

    public List<MenuItem> getAllMenuItems() {
        return new ArrayList<>(menuItems);
    }

    public List<MenuItem> getMenuItemsByCategory(Category category) {
        List<MenuItem> filtered = new ArrayList<>();
        for (MenuItem item : menuItems) {
            if (item.getCategory() == category) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    public Order placeOrder(String customerName, String tableInfo, OrderType orderType, List<OrderItem> cartItems) {
        validateOrder(customerName, cartItems);
        Order order = new Order(nextOrderId++, customerName.trim(), tableInfo.trim(), orderType, copyItems(cartItems));
        orders.add(order);
        return order;
    }

    public List<Order> getOrders() {
        return new ArrayList<>(orders);
    }

    public void advanceOrderStatus(int orderId) {
        for (Order order : orders) {
            if (order.getOrderId() == orderId) {
                switch (order.getStatus()) {
                    case PENDING:
                        order.setStatus(OrderStatus.PREPARING);
                        break;
                    case PREPARING:
                        order.setStatus(OrderStatus.READY);
                        break;
                    case READY:
                        order.setStatus(OrderStatus.COMPLETED);
                        break;
                    case COMPLETED:
                        break;
                }
                return;
            }
        }
    }

    private void validateOrder(String customerName, List<OrderItem> cartItems) {
        if (customerName == null || customerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer name is required.");
        }
        if (cartItems == null || cartItems.isEmpty()) {
            throw new IllegalArgumentException("Please add at least one menu item.");
        }
    }

    private List<OrderItem> copyItems(List<OrderItem> cartItems) {
        List<OrderItem> copy = new ArrayList<>();
        for (OrderItem item : cartItems) {
            copy.add(new OrderItem(item.getMenuItem(), item.getQuantity()));
        }
        return copy;
    }

    private List<MenuItem> buildDefaultMenu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(1, "Gunluk Corba", Category.APPETIZER, 170.00));
        items.add(new MenuItem(2, "Sigara Boregi", Category.APPETIZER, 160.00));
        items.add(new MenuItem(3, "Mevsim Salata", Category.APPETIZER, 150.00));
        items.add(new MenuItem(4, "Izgara Tavuk", Category.MAIN_COURSE, 510.00));
        items.add(new MenuItem(5, "Et Burger", Category.MAIN_COURSE, 480.00));
        items.add(new MenuItem(6, "Alfredo Makarna", Category.MAIN_COURSE, 430.00));
        items.add(new MenuItem(7, "Tavuk Dunyasi", Category.MAIN_COURSE, 460.00));
        items.add(new MenuItem(8, "Kofte Tabagi", Category.MAIN_COURSE, 440.00));
        items.add(new MenuItem(9, "Cizkek", Category.DESSERT, 200.00));
        items.add(new MenuItem(10, "Cikolatali Browni", Category.DESSERT, 180.00));
        items.add(new MenuItem(11, "Sutlac", Category.DESSERT, 140.00));
        items.add(new MenuItem(12, "Taze Meyve Suyu", Category.DRINK, 130.00));
        items.add(new MenuItem(13, "Kahve", Category.DRINK, 90.00));
        items.add(new MenuItem(14, "Maden Suyu", Category.DRINK, 60.00));
        items.add(new MenuItem(15, "Ayran", Category.DRINK, 70.00));
        return items;
    }
}