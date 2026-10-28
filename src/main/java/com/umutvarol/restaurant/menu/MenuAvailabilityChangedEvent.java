package com.umutvarol.restaurant.menu;

import java.util.List;

/**
 * Dishes went off sale or came back, by hand or because the stock ledger sold them out. Delivery platforms are told,
 * so guests there cannot order what the kitchen cannot make.
 */
public record MenuAvailabilityChangedEvent(List<Long> menuItemIds) {
}
