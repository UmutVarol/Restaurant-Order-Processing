package com.umutvarol.restaurant.menu.dto;

import com.umutvarol.restaurant.menu.DietaryTag;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record MenuItemResponse(
        Long id,
        String name,
        String description,
        MenuCategory category,
        BigDecimal price,
        String icon,
        int prepTimeMinutes,
        boolean popular,
        boolean available,
        Set<DietaryTag> tags,
        List<OptionGroupResponse> optionGroups) {

    public static MenuItemResponse from(MenuItem item) {
        return new MenuItemResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getCategory(),
                item.getPrice(),
                item.getIcon(),
                item.getPrepTimeMinutes(),
                item.isPopular(),
                item.isOrderable(),
                new TreeSet<>(item.getTags()),
                item.getOptionGroups().stream().map(OptionGroupResponse::from).toList());
    }
}
