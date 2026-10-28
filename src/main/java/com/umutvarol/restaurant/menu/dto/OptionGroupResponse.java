package com.umutvarol.restaurant.menu.dto;

import com.umutvarol.restaurant.menu.OptionGroup;
import java.math.BigDecimal;
import java.util.List;

public record OptionGroupResponse(Long id, String name, int minSelect, int maxSelect, List<Choice> choices) {

    public record Choice(Long id, String name, BigDecimal priceDelta) {
    }

    public static OptionGroupResponse from(OptionGroup group) {
        return new OptionGroupResponse(group.getId(), group.getName(), group.getMinSelect(), group.getMaxSelect(),
                group.getChoices().stream().map(c -> new Choice(c.getId(), c.getName(), c.getPriceDelta())).toList());
    }
}
