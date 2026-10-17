import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RestaurantOrderProcessingApp extends JFrame {
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

    public RestaurantOrderProcessingApp() {
        this.service = new RestaurantService();
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

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            RestaurantOrderProcessingApp app = new RestaurantOrderProcessingApp();
            app.setVisible(true);
        });
    }

    enum Category {
        APPETIZER("Appetizer"),
        MAIN_COURSE("Main Course"),
        DESSERT("Dessert"),
        DRINK("Drink");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    enum OrderStatus {
        PENDING("Pending"),
        PREPARING("Preparing"),
        READY("Ready"),
        COMPLETED("Completed");

        private final String displayName;

        OrderStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    enum OrderType {
        DINE_IN("Dine In"),
        DELIVERY("Delivery");

        private final String displayName;

        OrderType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    static class MenuItem {
        private final int id;
        private final String name;
        private final Category category;
        private final double price;

        public MenuItem(int id, String name, Category category, double price) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.price = price;
        }

        public int getId() {
            return id;
        }

        public Category getCategory() {
            return category;
        }

        public double getPrice() {
            return price;
        }

        @Override
        public String toString() {
            return name + " - " + Math.round(price) + " TL";
        }
    }

    static class OrderItem {
        private final MenuItem menuItem;
        private int quantity;

        public OrderItem(MenuItem menuItem, int quantity) {
            this.menuItem = menuItem;
            this.quantity = quantity;
        }

        public MenuItem getMenuItem() {
            return menuItem;
        }

        public int getQuantity() {
            return quantity;
        }

        public void increaseQuantity(int amount) {
            quantity += amount;
        }

        public double getLineTotal() {
            return menuItem.getPrice() * quantity;
        }

        @Override
        public String toString() {
            return menuItem.name + " x" + quantity + " - " + Math.round(getLineTotal()) + " TL";
        }
    }

    static class Order {
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
            return items;
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

    static class RestaurantService {
        private final List<MenuItem> menuItems;
        private final List<Order> orders;
        private int nextOrderId;

        public RestaurantService() {
            menuItems = buildDefaultMenu();
            orders = new ArrayList<>();
            nextOrderId = 1001;
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
}
