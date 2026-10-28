package com.umutvarol.restaurant.inventory;

import java.time.Instant;
import java.util.List;

/**
 * Stock moved in a way screens care about: ingredients that just fell to their low level, and dishes that just
 * sold out or came back. Tills reload the menu; managers get a warning.
 */
public record StockChangedEvent(List<String> lowIngredients, List<String> soldOut, List<String> backOnSale,
                                Instant occurredAt) {

    boolean matters() {
        return !lowIngredients.isEmpty() || !soldOut.isEmpty() || !backOnSale.isEmpty();
    }
}
