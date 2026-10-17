package com.umutvarol.restaurant.analytics;

import com.umutvarol.restaurant.menu.MenuCategory;
import java.math.BigDecimal;

/** Units sold and gross sales of a menu category over a period. */
public record CategorySales(MenuCategory category, Long quantity, BigDecimal revenue) {
}
