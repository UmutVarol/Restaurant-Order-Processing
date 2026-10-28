package com.umutvarol.restaurant.guest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One dish of a guest's cart, as it was priced when they sent it. */
@Entity
@Table(name = "guest_request_lines")
public class GuestRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private GuestRequest request;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(name = "item_name", nullable = false, length = 80)
    private String itemName;

    @Column(name = "item_icon", length = 16)
    private String itemIcon;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 140)
    private String note;

    @Column(name = "option_ids", length = 200)
    private String optionIds;

    @Column(name = "options_text", length = 255)
    private String optionsText;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected GuestRequestLine() {
        // for JPA
    }

    GuestRequestLine(GuestRequest request, Long menuItemId, String itemName, @Nullable String itemIcon, int quantity,
                     @Nullable String note, List<Long> optionIds, @Nullable String optionsText, BigDecimal lineTotal) {
        this.request = request;
        this.menuItemId = menuItemId;
        this.itemName = itemName;
        this.itemIcon = itemIcon;
        this.quantity = quantity;
        this.note = note;
        this.optionIds = optionIds.isEmpty() ? null : String.join(",", optionIds.stream().map(String::valueOf).toList());
        this.optionsText = optionsText;
        this.lineTotal = lineTotal;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getItemName() {
        return itemName;
    }

    public @Nullable String getItemIcon() {
        return itemIcon;
    }

    public int getQuantity() {
        return quantity;
    }

    public @Nullable String getNote() {
        return note;
    }

    public List<Long> getOptionIds() {
        return optionIds == null ? List.of() : Arrays.stream(optionIds.split(",")).map(Long::valueOf).toList();
    }

    public @Nullable String getOptionsText() {
        return optionsText;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
