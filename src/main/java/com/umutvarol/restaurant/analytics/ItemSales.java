package com.umutvarol.restaurant.analytics;

import java.math.BigDecimal;

/** Units sold and gross sales of a single menu item over a period. */
public record ItemSales(String name, String icon, Long quantity, BigDecimal revenue) {
}
