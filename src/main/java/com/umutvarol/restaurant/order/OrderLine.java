package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.OptionChoice;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
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

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderLineOption> options = new ArrayList<>();

    protected OrderLine() {
        // for JPA
    }

    private OrderLine(MenuItem item, int quantity, @Nullable String note, List<OptionChoice> choices) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        this.menuItemId = item.getId();
        this.itemName = item.getName();
        this.itemIcon = item.getIcon();
        this.category = item.getCategory();
        BigDecimal extras = choices.stream().map(OptionChoice::getPriceDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.unitPrice = Money.normalize(item.getPrice().add(extras));
        this.quantity = quantity;
        this.note = note == null || note.isBlank() ? null : note.strip();
        this.lineTotal = Money.normalize(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        choices.forEach(choice -> options.add(new OrderLineOption(this, choice.getId(), choice.getGroup().getName(),
                choice.getName(), choice.getPriceDelta())));
    }

    public static OrderLine of(MenuItem item, int quantity, @Nullable String note) {
        return new OrderLine(item, quantity, note, List.of());
    }

    /** A line with the given menu options; the unit price includes what they add. */
    public static OrderLine of(MenuItem item, int quantity, @Nullable String note, List<OptionChoice> choices) {
        return new OrderLine(item, quantity, note, choices);
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

    public List<OrderLineOption> getOptions() {
        return Collections.unmodifiableList(options);
    }

    /** "Bol acılı · Ekstra lavaş", or null for a line ordered as it comes. */
    public @Nullable String optionSummary() {
        return options.isEmpty() ? null
                : options.stream().map(OrderLineOption::getChoiceName).collect(Collectors.joining(" · "));
    }
}
