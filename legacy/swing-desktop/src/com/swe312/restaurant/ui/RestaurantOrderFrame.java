package com.swe312.restaurant.ui;

import com.swe312.restaurant.model.*;
import com.swe312.restaurant.service.RestaurantService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
// Çakışmayı önlemek için java.awt.* yerine sadece gerekenleri içe aktarıyoruz:
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class RestaurantOrderFrame extends JFrame {
    private final RestaurantService service;
    private final DefaultListModel<MenuItem> menuListModel;
    private final DefaultListModel<OrderItem> cartListModel;
    private final DefaultListModel<Order> orderHistoryModel;

    private final JTextField customerField;
    private final JTextField tableField;
    private final JComboBox<OrderType> orderTypeCombo;
    private final JComboBox<Object> categoryCombo;
    private final JSpinner quantitySpinner;

    private final JLabel subtotalLabel;
    private final JLabel taxLabel;
    private final JLabel totalLabel;

    private final JList<MenuItem> menuList;
    private final JList<OrderItem> cartList;
    private final JList<Order> orderHistoryList;

    public RestaurantOrderFrame() {
        this.service = RestaurantService.getInstance(); // Singleton kullanımı
        this.menuListModel = new DefaultListModel<>();
        this.cartListModel = new DefaultListModel<>();
        this.orderHistoryModel = new DefaultListModel<>();

        this.customerField = new JTextField();
        this.tableField = new JTextField();
        this.orderTypeCombo = new JComboBox<>(OrderType.values());
        this.categoryCombo = new JComboBox<>();
        this.quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));

        this.subtotalLabel = new JLabel("0 TL");
        this.taxLabel = new JLabel("0 TL");
        this.totalLabel = new JLabel("0 TL");

        this.menuList = new JList<>(menuListModel);
        this.cartList = new JList<>(cartListModel);
        this.orderHistoryList = new JList<>(orderHistoryModel);

        initializeFrame();
        buildUI();
        loadMenuItems(null);
        refreshOrderHistory();
    }

    private void initializeFrame() {
        setTitle("Restaurant Order Processing System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 680);
        setLocationRelativeTo(null);
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel titleLabel = new JLabel("Restaurant Order Processing");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 24));
        root.add(titleLabel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                buildOrderCreationPanel(),
                buildOrderManagementPanel()
        );
        splitPane.setDividerLocation(650);
        splitPane.setResizeWeight(0.62);

        root.add(splitPane, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildOrderCreationPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.add(buildCustomerPanel(), BorderLayout.NORTH);
        panel.add(buildCenterPanel(), BorderLayout.CENTER);
        panel.add(buildCheckoutPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildCustomerPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 3, 8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Customer Details"));

        panel.add(new JLabel("Customer Name"));
        panel.add(new JLabel("Table / Address"));
        panel.add(new JLabel("Order Type"));
        panel.add(customerField);
        panel.add(tableField);
        panel.add(orderTypeCombo);

        return panel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 12));
        panel.add(buildMenuPanel());
        panel.add(buildCartPanel());
        return panel;
    }

    private JPanel buildMenuPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Menu"));

        categoryCombo.addItem("All Categories");
        for (Category category : Category.values()) {
            categoryCombo.addItem(category);
        }

        categoryCombo.addActionListener(e -> {
            Object selected = categoryCombo.getSelectedItem();
            loadMenuItems(selected instanceof Category ? (Category) selected : null);
        });
        
        menuList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && menuList.getSelectedValue() != null) {
                    addSelectedItemToCart();
                }
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        topPanel.add(new JLabel("Category"), BorderLayout.WEST);
        topPanel.add(categoryCombo, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new BorderLayout(8, 8));
        actionPanel.add(new JLabel("Quantity"), BorderLayout.WEST);
        actionPanel.add(quantitySpinner, BorderLayout.CENTER);

        JButton addToCartButton = new JButton("Add To Cart");
        addToCartButton.addActionListener(e -> addSelectedItemToCart());
        actionPanel.add(addToCartButton, BorderLayout.EAST);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(menuList), BorderLayout.CENTER);
        panel.add(actionPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildCartPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Current Cart"));

        panel.add(new JScrollPane(cartList), BorderLayout.CENTER);

        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedCartItem());
        panel.add(removeButton, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildCheckoutPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Checkout"));

        JPanel totalsPanel = new JPanel(new GridLayout(3, 2, 8, 6));
        totalsPanel.add(new JLabel("Subtotal"));
        totalsPanel.add(subtotalLabel);
        totalsPanel.add(new JLabel("Tax (10%)"));
        totalsPanel.add(taxLabel);
        totalsPanel.add(new JLabel("Grand Total"));
        totalsPanel.add(totalLabel);

        JButton placeOrderButton = new JButton("Place Order");
        placeOrderButton.addActionListener(e -> placeOrder());

        panel.add(totalsPanel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(placeOrderButton);

        return panel;
    }

    private JPanel buildOrderManagementPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Order Tracking"));

        panel.add(new JScrollPane(orderHistoryList), BorderLayout.CENTER);

        JButton viewDetailsButton = new JButton("View Details");
        viewDetailsButton.addActionListener(e -> viewSelectedOrderDetails());

        JButton advanceStatusButton = new JButton("Advance Status");
        advanceStatusButton.addActionListener(e -> advanceSelectedOrderStatus());

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        buttonPanel.add(viewDetailsButton);
        buttonPanel.add(advanceStatusButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void loadMenuItems(Category category) {
        menuListModel.clear();
        List<MenuItem> items = (category == null)
                ? service.getAllMenuItems()
                : service.getMenuItemsByCategory(category);

        for (MenuItem item : items) {
            menuListModel.addElement(item);
        }
    }

    private void addSelectedItemToCart() {
        MenuItem selectedItem = menuList.getSelectedValue();
        if (selectedItem == null) {
            showMessage("Please select a menu item first.");
            return;
        }

        int quantity = (Integer) quantitySpinner.getValue();

        for (int i = 0; i < cartListModel.size(); i++) {
            OrderItem item = cartListModel.get(i);
            if (item.getMenuItem().getId() == selectedItem.getId()) {
                item.increaseQuantity(quantity);
                cartList.repaint();
                updateTotals();
                return;
            }
        }

        cartListModel.addElement(new OrderItem(selectedItem, quantity));
        updateTotals();
    }

    private void removeSelectedCartItem() {
        int selectedIndex = cartList.getSelectedIndex();
        if (selectedIndex >= 0) {
            cartListModel.remove(selectedIndex);
            updateTotals();
        }
    }

    private void updateTotals() {
        double subtotal = 0.0;
        for (int i = 0; i < cartListModel.size(); i++) {
            subtotal += cartListModel.get(i).getLineTotal();
        }

        double tax = subtotal * 0.10;
        double total = subtotal + tax;

        subtotalLabel.setText(formatCurrency(subtotal));
        taxLabel.setText(formatCurrency(tax));
        totalLabel.setText(formatCurrency(total));
    }

    private void placeOrder() {
        try {
            List<OrderItem> cartItems = new ArrayList<>();
            for (int i = 0; i < cartListModel.size(); i++) {
                cartItems.add(cartListModel.get(i));
            }

            Order order = service.placeOrder(
                    customerField.getText(),
                    tableField.getText(),
                    (OrderType) orderTypeCombo.getSelectedItem(),
                    cartItems
            );

            refreshOrderHistory();
            clearOrderForm();
            showMessage("Order #" + order.getOrderId() + " created successfully.");
        } catch (IllegalArgumentException ex) {
            showMessage(ex.getMessage());
        }
    }

    private void clearOrderForm() {
        customerField.setText("");
        tableField.setText("");
        quantitySpinner.setValue(1);
        cartListModel.clear();
        updateTotals();
    }

    private void refreshOrderHistory() {
        orderHistoryModel.clear();
        for (Order order : service.getOrders()) {
            orderHistoryModel.addElement(order);
        }
    }

    private void advanceSelectedOrderStatus() {
        Order selectedOrder = orderHistoryList.getSelectedValue();
        if (selectedOrder == null) {
            showMessage("Please select an order from the history list.");
            return;
        }

        service.advanceOrderStatus(selectedOrder.getOrderId());
        refreshOrderHistory();
    }

    private void viewSelectedOrderDetails() {
        Order selectedOrder = orderHistoryList.getSelectedValue();
        if (selectedOrder == null) {
            showMessage("Please select an order from the history list.");
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Order ID: ").append(selectedOrder.getOrderId()).append("\n");
        builder.append("Customer: ").append(selectedOrder.getCustomerName()).append("\n");
        builder.append("Table/Address: ").append(selectedOrder.getTableInfo()).append("\n");
        builder.append("Order Type: ").append(selectedOrder.getOrderType().getDisplayName()).append("\n");
        builder.append("Status: ").append(selectedOrder.getStatus().getDisplayName()).append("\n\n");
        builder.append("Items:\n");

        for (OrderItem item : selectedOrder.getItems()) {
            builder.append("- ").append(item).append("\n");
        }

        builder.append("\nTotal: ").append(formatCurrency(selectedOrder.getTotal()));
        JOptionPane.showMessageDialog(this, builder.toString(), "Order Details", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Restaurant Order Processing", JOptionPane.INFORMATION_MESSAGE);
    }

    private String formatCurrency(double amount) {
        return Math.round(amount) + " TL";
    }
}