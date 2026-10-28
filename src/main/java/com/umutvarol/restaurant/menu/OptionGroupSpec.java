package com.umutvarol.restaurant.menu;

import java.math.BigDecimal;
import java.util.List;

/** The options of a dish as the menu editor describes them, before they are checked and stored. */
public record OptionGroupSpec(String name, int minSelect, int maxSelect, List<Choice> choices) {

    public record Choice(String name, BigDecimal priceDelta) {
    }
}
