package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One line of an order. The item's name, icon, category and unit price are copied ("snapshotted") at the
 * moment the order is placed, so later menu edits never rewrite history or receipts.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(name = "item_name", nullable = false, length = 80)
    private String itemName;

    @Column(name = "item_icon", length = 16)
    private String itemIcon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuCategory category;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 140)
    private String note;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected OrderLine() {
        // for JPA
    }

    private OrderLine(MenuItem item, int quantity, @Nullable String note) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        this.menuItemId = item.getId();
        this.itemName = item.getName();
        this.itemIcon = item.getIcon();
        this.category = item.getCategory();
        this.unitPrice = item.getPrice();
        this.quantity = quantity;
        this.note = note == null || note.isBlank() ? null : note.strip();
        this.lineTotal = Money.normalize(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public static OrderLine of(MenuItem item, int quantity, @Nullable String note) {
        return new OrderLine(item, quantity, note);
    }

    void attachTo(Order order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemIcon() {
        return itemIcon;
    }

    public MenuCategory getCategory() {
        return category;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
