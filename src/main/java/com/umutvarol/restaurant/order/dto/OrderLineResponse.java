package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.order.OrderLine;
import java.math.BigDecimal;

public record OrderLineResponse(
        Long menuItemId,
        String name,
        String icon,
        MenuCategory category,
        BigDecimal unitPrice,
        int quantity,
        String note,
        BigDecimal lineTotal) {

    public static OrderLineResponse from(OrderLine line) {
        return new OrderLineResponse(
                line.getMenuItemId(),
                line.getItemName(),
                line.getItemIcon(),
                line.getCategory(),
                line.getUnitPrice(),
                line.getQuantity(),
                line.getNote(),
                line.getLineTotal());
    }
}
